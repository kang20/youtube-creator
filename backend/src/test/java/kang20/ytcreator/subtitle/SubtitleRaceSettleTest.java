package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import kang20.ytcreator.subtitle.internal.service.support.SignedUrlIssuer;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 낙관적 잠금에 <b>진 쪽</b>의 수렴 경로와 배치의 작업별 격리 — 실제 경쟁은
 * {@link SubtitleConcurrencyTest} 가 재현하고, 여기서는 쓰기 빈을 대역으로 바꿔
 * "충돌 → 재시도 → 멱등 경로 수렴"을 <b>결정적으로</b> 밟는다(타이밍 의존 커버리지 제거).
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubtitleTestClock.class})
class SubtitleRaceSettleTest {

	private static final LocalDateTime NOW = SubtitleTestClock.BASE;

	@Autowired
	private SubtitleJobPort subtitleJobPort;

	@Autowired
	private SubtitleWorkerPort subtitleWorkerPort;

	@Autowired
	private SubtitleTimeoutPort subtitleTimeoutPort;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private MutableClock clock;

	@MockitoBean
	private JobWriter jobWriter;

	@MockitoBean
	private WorkDispatcher workDispatcher;

	@MockitoBean
	private SignedUrlIssuer signedUrlIssuer;

	@MockitoBean
	private StorageInspector storageInspector;

	@BeforeEach
	void 초기화() {
		clock.setTo(SubtitleTestClock.BASE);
		jobRepository.deleteAll();
	}

	/** REQ-148 — 진 쪽은 재시도로 승자 상태에 수렴하고, 이긴 전이만 의뢰를 낸다 */
	@Test
	@DisplayName("낙관 충돌에 진 원본 수신은 재시도로 수렴한다")
	void 낙관_충돌에_진_원본_수신은_재시도로_수렴한다() {
		Job job = JobFixture.jobAt(JobStatus.CREATED, jobRepository, JobFixture.OWNER, NOW);
		when(storageInspector.exists(any())).thenReturn(true);
		when(jobWriter.receiveSource(eq(job.getId()), eq(JobFixture.OWNER)))
			.thenThrow(new OptimisticLockingFailureException("lost race"))
			.thenReturn(new TransitionResult(true, JobStatus.REQUEST_SCRIPT));

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		verify(jobWriter, times(2)).receiveSource(job.getId(), JobFixture.OWNER);
		verify(workDispatcher).dispatch(job.getId(), JobStatus.REQUEST_SCRIPT);
	}

	/** REQ-53 · REQ-148 — 진 확정은 오류가 아니라 현재 상태다. 산출 의뢰가 반복되면 파일이 두 벌 생긴다 */
	@Test
	@DisplayName("낙관 충돌에 진 확정은 현재 상태로 수렴하고 산출을 다시 의뢰하지 않는다")
	void 낙관_충돌에_진_확정은_현재_상태로_수렴하고_산출을_다시_의뢰하지_않는다() {
		Job job = JobFixture.jobAt(JobStatus.COMPLETED_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		when(storageInspector.scriptEmpty(any())).thenReturn(false);
		when(jobWriter.confirmScript(eq(job.getId()), eq(JobFixture.OWNER), eq(false)))
			.thenThrow(new OptimisticLockingFailureException("lost race"))
			.thenReturn(new TransitionResult(false, JobStatus.REQUEST_SUBTITLE));

		JobStatus status = subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		verifyNoInteractions(workDispatcher);
	}

	/** REQ-136 · REQ-148 — 진 완료 통지는 "무시하고 현재 상태" 경로로 수렴한다 */
	@Test
	@DisplayName("낙관 충돌에 진 완료 통지는 현재 상태로 수렴한다")
	void 낙관_충돌에_진_완료_통지는_현재_상태로_수렴한다() {
		JobId jobId = new JobId(11L);
		when(jobWriter.attachScript(eq(jobId), eq(JobFixture.SCRIPT_KEY)))
			.thenThrow(new OptimisticLockingFailureException("lost race"))
			.thenReturn(new TransitionResult(false, JobStatus.COMPLETED_SCRIPT));

		assertThat(subtitleWorkerPort.attachScript(jobId, JobFixture.SCRIPT_KEY))
			.isEqualTo(JobStatus.COMPLETED_SCRIPT);
	}

	/** 워커 완료와 재개 배치의 경쟁 — 충돌은 "상태가 나아갔다"라 조용히 다음 주기로 넘긴다 */
	@Test
	@DisplayName("재개 중 충돌은 오류가 아니라 다음 주기로 넘긴다")
	void 재개_중_충돌은_오류가_아니라_다음_주기로_넘긴다() {
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(jobWriter.redispatchIfStalled(any()))
			.thenThrow(new OptimisticLockingFailureException("worker advanced it"));

		subtitleTimeoutPort.redispatchStalled();   // 예외가 새어 나오면 배치가 죽는다

		verifyNoInteractions(workDispatcher);
	}

	/** REQ-148 — 재판정에서 "이미 나아갔다"로 밀린 재개는 의뢰를 내지 않는다 */
	@Test
	@DisplayName("재판정에서 밀린 재개는 의뢰를 내지 않는다")
	void 재판정에서_밀린_재개는_의뢰를_내지_않는다() {
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(jobWriter.redispatchIfStalled(any()))
			.thenReturn(new TransitionResult(false, JobStatus.COMPLETED_SCRIPT));

		subtitleTimeoutPort.redispatchStalled();

		verifyNoInteractions(workDispatcher);
	}

	/** REQ-69 의 격리 축 — 한 작업의 마감 실패가 나머지 작업의 마감을 막으면 안 된다 */
	@Test
	@DisplayName("마감 실패는 다른 작업의 마감을 막지 않는다")
	void 마감_실패는_다른_작업의_마감을_막지_않는다() {
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		clock.setTo(NOW.plus(Job.JOB_TIMEOUT).plusMinutes(1));
		when(jobWriter.closeIfTimedOut(any()))
			.thenThrow(new IllegalStateException("first close failed"))
			.thenReturn(new TransitionResult(true, JobStatus.FAILURE));

		subtitleTimeoutPort.closeTimedOut();

		verify(jobWriter, times(2)).closeIfTimedOut(any());
	}

	/** 재개 배치도 같은 격리 — 실패는 그 작업의 일이고 나머지는 계속 간다 */
	@Test
	@DisplayName("재개 실패는 다른 작업의 재개를 막지 않는다")
	void 재개_실패는_다른_작업의_재개를_막지_않는다() {
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		JobFixture.jobAt(JobStatus.REQUEST_SCRIPT, jobRepository, JobFixture.OWNER, NOW);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(jobWriter.redispatchIfStalled(any()))
			.thenThrow(new IllegalStateException("first redispatch failed"))
			.thenReturn(new TransitionResult(true, JobStatus.REQUEST_SCRIPT));

		subtitleTimeoutPort.redispatchStalled();

		verify(jobWriter, times(2)).redispatchIfStalled(any());
		verify(workDispatcher, times(1)).dispatch(any(), eq(JobStatus.REQUEST_SCRIPT));
	}
}
