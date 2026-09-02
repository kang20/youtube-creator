package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import kang20.ytcreator.subtitle.internal.service.support.SignedUrlIssuer;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 워커 완료 통지({@code SubtitleWorkerPort}) — 같은 단계의 통지가 두 번 와도 상태는 한 번만
 * 나아간다(subtitle-v3 작업 규칙). 재시도·재전송·재개가 <b>정상적으로</b> 중복을 만든다.
 * 통지는 힌트고 근거는 산출물이다 — 서버가 정한 위치에 실물이 없으면 거절한다(v3).
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubtitleTestClock.class})
class SubtitleWorkerServiceTest {

	private static final LocalDateTime NOW = SubtitleTestClock.BASE;

	@Autowired
	private SubtitleWorkerPort subtitleWorkerPort;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private MutableClock clock;

	@MockitoBean
	private PaymentUsagePort paymentUsagePort;

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
		when(storageInspector.exists(any())).thenReturn(true);   // 기본은 "산출물이 있다" — 없는 경우만 따로 뒤집는다
	}

	private Job jobAt(JobStatus status) {
		return JobFixture.jobAt(status, jobRepository, JobFixture.OWNER, NOW, workDispatcher);
	}

	private static String ref(Job job) {
		return String.valueOf(job.getId().longValue());
	}

	private Job reload(Job job) {
		return jobRepository.findById(job.getId()).orElseThrow();
	}

	// ── attachScript ───────────────────────────────────────────────────

	/** REQ-41 · REQ-73 · REQ-118 — 내용 검사 없이 접수한다(0행 대본도 성공). 이용권을 다시 묻지 않는다 */
	@Test
	@DisplayName("대본 통지로 작업 번호의 대본 위치가 달리고 사용자 확정 대기로 넘어간다")
	void 대본_통지로_대본이_달리고_사용자_확정_대기로_넘어간다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		JobStatus status = subtitleWorkerPort.attachScript(job.getId());

		assertThat(status).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(reload(job).getScript()).isEqualTo(StorageKey.scriptOf(job.getId()));
		verify(storageInspector).exists(StorageKey.scriptOf(job.getId()));
		verify(paymentUsagePort, never()).consume(any(UserId.class), any());
	}

	/** 통지는 힌트다 — 실물 없이 대기 구간에 들어가면 깨진 편집 화면을 보다 24시간 뒤 방치로 닫힌다(v3) */
	@Test
	@DisplayName("대본 실물이 없는 완료 통지는 거절되고 상태는 그대로다")
	void 대본_실물이_없는_완료_통지는_거절되고_상태는_그대로다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		when(storageInspector.exists(StorageKey.scriptOf(job.getId()))).thenReturn(false);

		assertThatThrownBy(() -> subtitleWorkerPort.attachScript(job.getId()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);

		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(reload(job).getScript()).isNull();
	}

	/** REQ-87 · REQ-136 · REQ-171 — 중복 통지는 오류가 아니라 현재 상태다. 결과물은 한 벌이다 */
	@Test
	@DisplayName("중복 대본 통지는 한 번만 나아간다")
	void 중복_대본_통지는_한_번만_나아간다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		subtitleWorkerPort.attachScript(job.getId());
		LocalDateTime firstTransition = reload(job).getLastTransitionedAt();

		JobStatus retried = subtitleWorkerPort.attachScript(job.getId());

		assertThat(retried).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(reload(job).getLastTransitionedAt()).isEqualTo(firstTransition);
	}

	/** REQ-147 — 의뢰한 적 없는 완료 통지는 거부된다 */
	@Test
	@DisplayName("의뢰한 적 없는 대본 통지는 거부된다")
	void 의뢰한_적_없는_대본_통지는_거부된다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> subtitleWorkerPort.attachScript(job.getId()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	/** REQ-111 — 지난 단계·닫힌 작업의 통지는 무시하고 현재 상태를 돌려준다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"REQUEST_SUBTITLE", "FAILURE"})
	@DisplayName("지난 단계·닫힌 작업의 대본 통지는 무시된다")
	void 지난_단계와_닫힌_작업의_대본_통지는_무시된다(JobStatus status) {
		Job job = jobAt(status);

		assertThat(subtitleWorkerPort.attachScript(job.getId())).isEqualTo(status);
	}

	/** REQ-187 — 워커 통지도 없는 작업이면 같은 답이다(쓰기 빈의 find 방어선) */
	@Test
	@DisplayName("없는 작업의 통지는 없는 작업 답이다")
	void 없는_작업의_통지는_없는_작업_답이다() {
		assertThatThrownBy(() -> subtitleWorkerPort.attachScript(new JobId(987654321L)))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
	}

	// ── attachSubtitle ─────────────────────────────────────────────────

	/** REQ-66 · REQ-129 · REQ-131 · REQ-180 — 완료로 닫히면 commit 으로 소모를 확정한다. 멱등 키는 jobRef 다 */
	@Test
	@DisplayName("자막 통지는 완료로 닫고 소모를 확정한다")
	void 자막_통지는_완료로_닫고_소모를_확정한다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);

		JobStatus status = subtitleWorkerPort.attachSubtitle(job.getId());

		assertThat(status).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(reload(job).getSubtitle()).isEqualTo(StorageKey.subtitleOf(job.getId()));
		verify(storageInspector).exists(StorageKey.subtitleOf(job.getId()));
		verify(paymentUsagePort).commit(ref(job));
	}

	/** 자막도 같다 — 실물 없는 완료는 완료가 아니고, 소모 확정도 일어나지 않는다(v3) */
	@Test
	@DisplayName("자막 실물이 없는 완료 통지는 거절되고 소모도 확정되지 않는다")
	void 자막_실물이_없는_완료_통지는_거절되고_소모도_확정되지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		when(storageInspector.exists(StorageKey.subtitleOf(job.getId()))).thenReturn(false);

		assertThatThrownBy(() -> subtitleWorkerPort.attachSubtitle(job.getId()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);

		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		verify(paymentUsagePort, never()).commit(any());
	}

	/** REQ-70 · REQ-136 — 중복 통지가 소모 확정을 반복하면 무료 이용권이 샌다 */
	@Test
	@DisplayName("중복 자막 통지에 소모 확정은 두 번 불리지 않는다")
	void 중복_자막_통지에_소모_확정은_두_번_불리지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		subtitleWorkerPort.attachSubtitle(job.getId());

		JobStatus retried = subtitleWorkerPort.attachSubtitle(job.getId());

		assertThat(retried).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		verify(paymentUsagePort, times(1)).commit(ref(job));
	}

	/** REQ-147 — 산출을 의뢰한 적 없는 자막 통지는 거부된다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "COMPLETED_SCRIPT"})
	@DisplayName("의뢰 전 자막 통지는 거부된다")
	void 의뢰_전_자막_통지는_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> subtitleWorkerPort.attachSubtitle(job.getId()))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
		verify(paymentUsagePort, never()).commit(any());
	}

	/** REQ-111 — 닫힌 작업의 통지는 무시되고, 소모 확정도 일어나지 않는다 */
	@Test
	@DisplayName("닫힌 작업의 자막 통지는 무시되고 확정도 없다")
	void 닫힌_작업의_자막_통지는_무시되고_확정도_없다() {
		Job job = jobAt(JobStatus.FAILURE);

		assertThat(subtitleWorkerPort.attachSubtitle(job.getId())).isEqualTo(JobStatus.FAILURE);
		verify(paymentUsagePort, never()).commit(any());
	}

	/** REQ-69 — 확정 호출이 유실되면 조용히 넘어가지 않는다 — 전이와 한 트랜잭션이라 함께 되돌아간다 */
	@Test
	@DisplayName("소모 확정 실패는 완료 전이를 되돌린다 — 재전송이 다시 기회를 가진다")
	void 소모_확정_실패는_완료_전이를_되돌린다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		doThrow(new IllegalStateException("payment unavailable")).when(paymentUsagePort).commit(any());

		assertThatThrownBy(() -> subtitleWorkerPort.attachSubtitle(job.getId()))
			.isInstanceOf(IllegalStateException.class);

		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
	}
}
