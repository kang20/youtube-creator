package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
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
 * 타임아웃 마감·멈춘 작업 회복 배치({@code SubtitleTimeoutPort}) — {@code FailureCause} 하나가
 * 돈의 방향을 정한다(subtitle-v3 실패 사유). 시간은 {@link MutableClock} 로 옮긴다 —
 * 통과가 실행 시각에 달리면 안 된다(testing.md 작성 원칙 4).
 * 재개 의뢰는 아웃박스를 거쳐 큐 대역({@code WorkDispatcher})에 닿는다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubtitleTestClock.class})
class SubtitleTimeoutServiceTest {

	private static final LocalDateTime NOW = SubtitleTestClock.BASE;

	@Autowired
	private SubtitleTimeoutPort subtitleTimeoutPort;

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JobWriter jobWriter;

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

	// ── 방치 마감 (24h) ─────────────────────────────────────────────────

	/** REQ-30 · REQ-31 · REQ-133 · REQ-135 · REQ-149 · REQ-151 · REQ-152 · REQ-161 — 방치는 커밋이지 회복이 아니다 */
	@Test
	@DisplayName("방치된 대기 작업은 ABANDONED 로 닫고 소모를 확정한다 — 되돌리지 않는다")
	void 방치된_대기_작업은_ABANDONED_로_닫고_소모를_확정한다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		clock.setTo(NOW.plus(Job.JOB_TIMEOUT).plusMinutes(1));

		subtitleTimeoutPort.closeTimedOut();

		Job closed = reload(job);
		assertThat(closed.getStatus()).isEqualTo(JobStatus.FAILURE);
		assertThat(closed.getFailureCause()).isEqualTo(FailureCause.ABANDONED);
		verify(paymentUsagePort).commit(ref(job));
		verify(paymentUsagePort, never()).release(any());
	}

	/** REQ-27 · REQ-28 · REQ-66 · REQ-68 · REQ-90 · REQ-135 · REQ-149 · REQ-151 — 시스템 구간의 상한 초과는 서버 잘못이다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "REQUEST_SUBTITLE"})
	@DisplayName("시스템 구간의 상한 초과는 SERVER_FAULT 로 닫고 이용권을 되돌린다")
	void 시스템_구간의_상한_초과는_SERVER_FAULT_로_닫고_이용권을_되돌린다(JobStatus status) {
		Job job = jobAt(status);
		clock.setTo(NOW.plus(Job.JOB_TIMEOUT).plusMinutes(1));

		subtitleTimeoutPort.closeTimedOut();

		Job closed = reload(job);
		assertThat(closed.getStatus()).isEqualTo(JobStatus.FAILURE);
		assertThat(closed.getFailureCause()).isEqualTo(FailureCause.SERVER_FAULT);
		verify(paymentUsagePort).release(ref(job));
		verify(paymentUsagePort, never()).commit(any());
	}

	/** REQ-133 — 상한 이내는 후보가 아니다 */
	@Test
	@DisplayName("상한 이내의 작업은 건드리지 않는다")
	void 상한_이내의_작업은_건드리지_않는다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		clock.setTo(NOW.plusHours(23));

		subtitleTimeoutPort.closeTimedOut();

		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		verifyNoInteractions(paymentUsagePort);
	}

	/** REQ-70 — 이미 닫힌 작업을 다시 닫으면 보상이 반복된다 — 종결 상태는 후보에서 빠진다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("종결된 작업은 마감 대상이 아니라 이용권 보상이 반복되지 않는다")
	void 종결된_작업은_마감_대상이_아니라_이용권_보상이_반복되지_않는다(JobStatus status) {
		Job job = jobAt(status);
		clock.setTo(NOW.plusDays(3));

		subtitleTimeoutPort.closeTimedOut();

		assertThat(reload(job).getStatus()).isEqualTo(status);
		verifyNoInteractions(paymentUsagePort);
	}

	/** REQ-69 — 보상 통지 유실은 최대의 금전 리스크다. 실패하면 마감이 되돌아가 다음 배치가 다시 시도한다 */
	@Test
	@DisplayName("보상 호출 실패는 마감을 되돌려 다음 배치가 다시 시도한다 — 한 작업의 실패가 다른 작업을 막지 않는다")
	void 보상_호출_실패는_마감을_되돌려_다음_배치가_다시_시도한다() {
		Job first = jobAt(JobStatus.REQUEST_SCRIPT);
		Job second = jobAt(JobStatus.REQUEST_SCRIPT);
		clock.setTo(NOW.plus(Job.JOB_TIMEOUT).plusMinutes(1));
		doThrow(new IllegalStateException("payment unavailable")).when(paymentUsagePort).release(any());

		subtitleTimeoutPort.closeTimedOut();   // 예외가 새어 나오면 배치 전체가 죽는다

		assertThat(reload(first).getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(reload(second).getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		verify(paymentUsagePort, times(2)).release(any());   // 격리 — 둘 다 시도됐다
	}

	// ── 멈춘 작업 재개 (30분) ───────────────────────────────────────────

	/** REQ-15 · REQ-84 · REQ-85 · REQ-86 · REQ-116 · REQ-164 — 산출물이 없으면 멈춘 그 단계를 다시 시킨다 */
	@Test
	@DisplayName("산출물 없이 멈춘 작업은 같은 단계로 다시 의뢰된다 — 재개 창을 새로 연다")
	void 멈춘_작업은_같은_단계로_다시_의뢰된다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		LocalDateTime detectedAt = NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1);
		clock.setTo(detectedAt);

		subtitleTimeoutPort.redispatchStalled();

		Job redispatched = reload(job);
		assertThat(redispatched.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(redispatched.getRedispatchCount()).isEqualTo(1);
		assertThat(redispatched.getLastTransitionedAt()).isEqualTo(detectedAt);
		verify(storageInspector).exists(StorageKey.scriptOf(job.getId()));   // 재투입 전에 산출물부터 본다
		verify(workDispatcher, timeout(5000)).dispatch(job.getId(), WorkStage.SCRIPT);
		verifyNoInteractions(paymentUsagePort);
	}

	/** REQ-86 · REQ-126 — 자막 산출 단계의 멈춤도 다음 단계가 아니라 그 단계다 */
	@Test
	@DisplayName("자막 단계의 멈춤도 그 단계로 다시 의뢰된다")
	void 자막_단계의_멈춤도_그_단계로_다시_의뢰된다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));

		subtitleTimeoutPort.redispatchStalled();

		verify(workDispatcher, timeout(5000)).dispatch(job.getId(), WorkStage.SUBTITLE);
		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
	}

	/** 조정(reconcile) — 통지만 유실된 작업은 산출물로 전진시키고 워커를 다시 부르지 않는다(v3) */
	@Test
	@DisplayName("대본이 이미 있는 멈춘 작업은 워커 없이 사용자 확정 대기로 전진한다")
	void 대본이_이미_있는_멈춘_작업은_워커_없이_전진한다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(storageInspector.exists(StorageKey.scriptOf(job.getId()))).thenReturn(true);

		subtitleTimeoutPort.redispatchStalled();

		Job reconciled = reload(job);
		assertThat(reconciled.getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(reconciled.getScript()).isEqualTo(StorageKey.scriptOf(job.getId()));
		assertThat(reconciled.getRedispatchCount()).isZero();
		verifyNoInteractions(workDispatcher, paymentUsagePort);
	}

	/** 조정 — 자막이 이미 있으면 완료로 닫고 소모를 확정한다. 완료 통지 경로와 같은 부수효과다(v3) */
	@Test
	@DisplayName("자막이 이미 있는 멈춘 작업은 워커 없이 완료로 닫고 소모를 확정한다")
	void 자막이_이미_있는_멈춘_작업은_워커_없이_완료로_닫는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(storageInspector.exists(StorageKey.subtitleOf(job.getId()))).thenReturn(true);

		subtitleTimeoutPort.redispatchStalled();

		Job reconciled = reload(job);
		assertThat(reconciled.getStatus()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(reconciled.getSubtitle()).isEqualTo(StorageKey.subtitleOf(job.getId()));
		verify(paymentUsagePort).commit(ref(job));
		verifyNoInteractions(workDispatcher);
	}

	/** 조정 중 저장소 확인 실패는 그 작업만 건너뛴다 — 다음 주기가 다시 보고, 다른 작업은 계속 간다 */
	@Test
	@DisplayName("저장소 확인 실패는 그 작업만 건너뛰고 다른 작업의 재개를 막지 않는다")
	void 저장소_확인_실패는_그_작업만_건너뛴다() {
		Job first = jobAt(JobStatus.REQUEST_SCRIPT);
		Job second = jobAt(JobStatus.REQUEST_SCRIPT);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		when(storageInspector.exists(StorageKey.scriptOf(first.getId())))
			.thenThrow(new IllegalStateException("storage unavailable"));

		subtitleTimeoutPort.redispatchStalled();   // 예외가 새어 나오면 배치 전체가 죽는다

		assertThat(reload(first).getRedispatchCount()).isZero();
		assertThat(reload(second).getRedispatchCount()).isEqualTo(1);
		verify(workDispatcher, timeout(5000)).dispatch(second.getId(), WorkStage.SCRIPT);
		verify(workDispatcher, never()).dispatch(first.getId(), WorkStage.SCRIPT);
	}

	/** REQ-164 — 임계 이내는 멈춘 것이 아니다 */
	@Test
	@DisplayName("임계 이내면 재개하지 않는다 — 저장소도 묻지 않는다")
	void 임계_이내면_재개하지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		clock.setTo(NOW.plusMinutes(29));

		subtitleTimeoutPort.redispatchStalled();

		assertThat(reload(job).getRedispatchCount()).isZero();
		verifyNoInteractions(workDispatcher, storageInspector);
	}

	/** REQ-89 · REQ-143 · REQ-165 — 섞으면 사용자를 기다리는 작업을 시스템이 계속 다시 돌린다 */
	@Test
	@DisplayName("사용자 대기 구간은 재개 대상이 아니다")
	void 사용자_대기_구간은_재개_대상이_아니다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		clock.setTo(NOW.plusHours(3));

		subtitleTimeoutPort.redispatchStalled();

		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		verifyNoInteractions(workDispatcher, storageInspector);
	}

	/** REQ-27 · REQ-135 · REQ-167 — 재개 한계(3회)를 다 쓰면 회복 한계다 — SERVER_FAULT + release */
	@Test
	@DisplayName("재개 한계를 다 쓰면 SERVER_FAULT 로 닫고 이용권을 되돌린다")
	void 재개_한계를_다_쓰면_SERVER_FAULT_로_닫고_이용권을_되돌린다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		LocalDateTime at = NOW;
		for (int round = 1; round <= Job.REDISPATCH_LIMIT; round++) {
			at = at.plus(Job.STALL_THRESHOLD).plusMinutes(1);
			clock.setTo(at);
			subtitleTimeoutPort.redispatchStalled();
		}
		verify(workDispatcher, timeout(5000).times(Job.REDISPATCH_LIMIT)).dispatch(job.getId(), WorkStage.SCRIPT);

		clock.setTo(at.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		subtitleTimeoutPort.redispatchStalled();   // 4번째 필요 시점 — 더 시키지 않고 닫는다

		Job closed = reload(job);
		assertThat(closed.getStatus()).isEqualTo(JobStatus.FAILURE);
		assertThat(closed.getFailureCause()).isEqualTo(FailureCause.SERVER_FAULT);
		verify(paymentUsagePort).release(ref(job));
		verify(workDispatcher, times(Job.REDISPATCH_LIMIT)).dispatch(any(), any());
	}

	/** REQ-148 — 후보 조회와 마감 사이에 상태가 나아갔으면 같은 트랜잭션의 재판정이 마감을 접는다 */
	@Test
	@DisplayName("마감 재판정은 그새 나아간 작업을 닫지 않는다")
	void 마감_재판정은_그새_나아간_작업을_닫지_않는다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);   // 후보로 잡힌 뒤 사용자가 확정했다고 가정한 재판정 시점

		TransitionResult result = jobWriter.closeIfTimedOut(job.getId());

		assertThat(result.advanced()).isFalse();
		assertThat(reload(job).getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		verifyNoInteractions(paymentUsagePort);
	}

	/** REQ-148 — 후보 조회와 재개 사이에 워커가 끝냈으면 재판정이 재의뢰를 접는다 */
	@Test
	@DisplayName("재개 재판정은 그새 나아간 작업을 다시 시키지 않는다")
	void 재개_재판정은_그새_나아간_작업을_다시_시키지_않는다() {
		Job fresh = jobAt(JobStatus.REQUEST_SCRIPT);        // 전이 시각이 방금이라 더는 멈춘 작업이 아니다
		Job advanced = jobAt(JobStatus.COMPLETED_SCRIPT);   // 그새 워커가 끝내 의뢰 구간을 벗어났다

		TransitionResult freshResult = jobWriter.redispatchIfStalled(fresh.getId());
		TransitionResult advancedResult = jobWriter.redispatchIfStalled(advanced.getId());

		assertThat(freshResult.advanced()).isFalse();
		assertThat(advancedResult.advanced()).isFalse();
		assertThat(reload(fresh).getRedispatchCount()).isZero();
		assertThat(reload(advanced).getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		verifyNoInteractions(paymentUsagePort, workDispatcher);
	}

	/** REQ-170 — 재의뢰가 큐에 닿지 못해도 작업은 닫히지 않는다 — 상태가 진실이라 다음 주기가 다시 본다 */
	@Test
	@DisplayName("재개 의뢰가 큐에 닿지 못해도 작업을 닫지 않는다")
	void 재개_의뢰_실패는_작업을_닫지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);
		clock.setTo(NOW.plus(Job.STALL_THRESHOLD).plusMinutes(1));
		doThrow(new IllegalStateException("queue unavailable")).when(workDispatcher).dispatch(any(), any());

		subtitleTimeoutPort.redispatchStalled();   // 예외가 새어 나오면 안 된다

		Job kept = reload(job);
		assertThat(kept.getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(kept.getFailureCause()).isNull();
		verifyNoInteractions(paymentUsagePort);
	}
}
