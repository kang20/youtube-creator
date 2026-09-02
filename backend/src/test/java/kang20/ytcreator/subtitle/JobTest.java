package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
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
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 작업(Job) 애그리거트의 전이 매트릭스 — 정본은 subtitle-v3 전이표("아래 표에 없는 전이는 없다").
 *
 * <p>상태는 전부 <b>공개 행위로만</b> 만든다({@link JobFixture}). {@code receiveSource} 가 식별자로
 * 원본 키를 채번하므로 실제 저장(H2)을 거친다 — 리포지토리는 모킹하지 않는다(testing.md 작성 원칙 2).
 * 워커 의뢰 사건은 등록만 되고 <b>저장이 발행</b>하므로 {@link PublishedEvents} 로 본다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubtitleTestClock.class})
class JobTest {

	private static final LocalDateTime NOW = SubtitleTestClock.BASE;
	private static final LocalDateTime LATER = NOW.plusMinutes(5);

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
	}

	private Job jobAt(JobStatus status) {
		return JobFixture.jobAt(status, jobRepository, JobFixture.OWNER, NOW, workDispatcher);
	}

	// ── open ───────────────────────────────────────────────────────────

	/** REQ-110 — JobStatus.CREATED 로 생성된다 */
	@Test
	@DisplayName("열린 작업은 CREATED 로 태어나고 소유자와 전이 시각이 기록된다")
	void 열린_작업은_CREATED_로_태어난다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThat(job.getStatus()).isEqualTo(JobStatus.CREATED);
		assertThat(job.getUserId()).isEqualTo(JobFixture.OWNER);
		assertThat(job.getLastTransitionedAt()).isEqualTo(NOW);
		assertThat(job.getSource()).isNull();
	}

	// ── receiveSource ──────────────────────────────────────────────────

	/** REQ-8 · REQ-115 — 원본 키가 저장되면 REQUEST_SCRIPT 로 전이한다 */
	@Test
	@DisplayName("원본 수신 확인은 원본 키를 채번하고 REQUEST_SCRIPT 로 전이한다")
	void 원본_수신_확인은_원본_키를_채번하고_REQUEST_SCRIPT_로_전이한다() {
		Job job = jobAt(JobStatus.CREATED);

		boolean advanced = job.receiveSource(LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(job.getSource()).isEqualTo(StorageKey.sourceOf(job.getId()));
		assertThat(job.getLastTransitionedAt()).isEqualTo(LATER);
	}

	/** 워커 의뢰 사건 — 등록은 작업이, 발행은 저장이 한다(v3). 부르는 것을 잊으면 유실되는 수동 발행이 없다 */
	@Test
	@DisplayName("원본 수신 확인은 SCRIPT 의뢰 사건을 등록하고 저장이 발행한다")
	void 원본_수신_확인은_SCRIPT_의뢰_사건을_등록하고_저장이_발행한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.CREATED);
		job.receiveSource(LATER);

		jobRepository.save(job);

		assertThat(events.ofType(WorkRequested.class))
			.containsExactly(WorkRequested.of(job.getId(), WorkStage.SCRIPT));
	}

	/** REQ-140 — 나아가지 않은 입력은 전이 시각을 갱신하지 않는다. 갱신하면 멈춘 작업이 영원히 안 잡힌다 */
	@Test
	@DisplayName("원본 수신 재요청은 오류가 아니라 무시되고 시각도 그대로다 — 의뢰도 다시 등록하지 않는다")
	void 원본_수신_재요청은_오류가_아니라_무시되고_시각도_그대로다(PublishedEvents events) {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		boolean advanced = job.receiveSource(LATER);
		jobRepository.save(job);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(job.getLastTransitionedAt()).isEqualTo(NOW);
		assertThat(events.ofType(WorkRequested.class)).hasSize(1);   // 픽스처가 만든 첫 의뢰뿐이다
	}

	/** REQ-147 — 닫힌 작업에는 시작할 자리가 없다 */
	@Test
	@DisplayName("닫힌 작업의 원본 수신은 거부된다")
	void 닫힌_작업의_원본_수신은_거부된다() {
		Job job = jobAt(JobStatus.FAILURE);

		assertThatThrownBy(() -> job.receiveSource(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	// ── attachScript ───────────────────────────────────────────────────

	/** REQ-17 · REQ-119 — 작업 번호로 정해진 대본 위치를 달고 사용자 확정 대기로 넘어간다(v3) */
	@Test
	@DisplayName("대본 통지는 작업 번호로 정해진 대본 위치를 달고 COMPLETED_SCRIPT 로 전이한다")
	void 대본_통지는_대본을_달고_COMPLETED_SCRIPT_로_전이한다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		boolean advanced = job.attachScript(LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(job.getScript()).isEqualTo(StorageKey.scriptOf(job.getId()));
		assertThat(job.getLastTransitionedAt()).isEqualTo(LATER);
	}

	/** REQ-147 — 의뢰한 적 없는 완료 통지는 무시가 아니라 거부다 */
	@Test
	@DisplayName("의뢰한 적 없는 대본 통지는 거부된다")
	void 의뢰한_적_없는_대본_통지는_거부된다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> job.attachScript(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	/** REQ-136 · REQ-140 — 같은 단계의 통지가 두 번 와도 상태는 한 번만 나아가고 전이 시각이 남는다 */
	@Test
	@DisplayName("중복 대본 통지는 무시되고 첫 전이 시각이 남는다")
	void 중복_대본_통지는_무시되고_첫_전이_시각이_남는다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		boolean advanced = job.attachScript(LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(job.getScript()).isEqualTo(StorageKey.scriptOf(job.getId()));
		assertThat(job.getLastTransitionedAt()).isEqualTo(NOW);
	}

	/** REQ-111 — 이미 지난 단계의 통지가 상태를 되돌리면 안 된다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"REQUEST_SUBTITLE", "COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("지난 단계·닫힌 작업의 대본 통지는 무시된다 — 상태가 되돌아가지 않는다")
	void 지난_단계의_대본_통지는_무시된다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.attachScript(LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
	}

	// ── confirmScript ──────────────────────────────────────────────────

	/** REQ-127 · REQ-150 — 확정은 머무는 상태 없이 곧장 REQUEST_SUBTITLE 로 간다 */
	@Test
	@DisplayName("확정은 내용이 있으면 REQUEST_SUBTITLE 로 간다 — 머무는 확정 상태는 없다")
	void 확정은_내용이_있으면_REQUEST_SUBTITLE_로_간다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		boolean advanced = job.confirmScript(false, LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(job.getLastTransitionedAt()).isEqualTo(LATER);
	}

	/** 워커 의뢰 사건 — 확정도 같은 길이다(v3). 픽스처의 SCRIPT 의뢰 위에 SUBTITLE 의뢰가 하나 얹힌다 */
	@Test
	@DisplayName("확정은 SUBTITLE 의뢰 사건을 등록하고 저장이 발행한다")
	void 확정은_SUBTITLE_의뢰_사건을_등록하고_저장이_발행한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		job.confirmScript(false, LATER);

		jobRepository.save(job);

		assertThat(events.ofType(WorkRequested.class))
			.filteredOn(requested -> requested.stage() == WorkStage.SUBTITLE)
			.containsExactly(WorkRequested.of(job.getId(), WorkStage.SUBTITLE));
	}

	/** REQ-42 — 빈 대본은 만들 것이 없다. 실패가 아니라 성공으로 닫고, 워커를 부르지 않는다 */
	@Test
	@DisplayName("빈 대본 확정은 워커를 거치지 않고 곧장 완료로 간다 — 의뢰 사건도 없다")
	void 빈_대본_확정은_워커를_거치지_않고_곧장_완료로_간다(PublishedEvents events) {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		boolean advanced = job.confirmScript(true, LATER);
		jobRepository.save(job);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(job.getSubtitle()).isNull();
		assertThat(events.ofType(WorkRequested.class)).noneMatch(requested -> requested.stage() == WorkStage.SUBTITLE);
	}

	/** REQ-138 · REQ-54 — 확정 재요청은 오류가 아니다. 산출을 두 번 돌리면 파일이 두 벌 생긴다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"REQUEST_SUBTITLE", "COMPLETED_SUBTITLE"})
	@DisplayName("확정 재요청은 오류가 아니라 현재 상태를 돌려준다 — 되돌아가지도 않는다")
	void 확정_재요청은_오류가_아니라_현재_상태를_돌려준다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.confirmScript(false, LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
		assertThat(job.getLastTransitionedAt()).isEqualTo(NOW);
	}

	/** REQ-137 — 확정은 사용자 대기 구간(COMPLETED_SCRIPT)에서만 받는다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "FAILURE"})
	@DisplayName("대기 구간 밖의 확정은 거부된다")
	void 대기_구간_밖의_확정은_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.confirmScript(false, LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	// ── attachSubtitle ─────────────────────────────────────────────────

	/** REQ-23 · REQ-130 — 작업 번호로 정해진 자막 위치를 달고 완료로 닫는다(v3) */
	@Test
	@DisplayName("자막 통지는 작업 번호로 정해진 자막 위치를 달고 완료로 전이한다")
	void 자막_통지는_자막을_달고_완료로_전이한다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);

		boolean advanced = job.attachSubtitle(LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(job.getSubtitle()).isEqualTo(StorageKey.subtitleOf(job.getId()));
	}

	/** REQ-111 · REQ-136 — 완료·실패 뒤의 자막 통지는 무시된다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("중복·닫힌 작업의 자막 통지는 무시된다")
	void 중복_자막_통지는_무시된다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.attachSubtitle(LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
	}

	/** REQ-147 — 산출을 의뢰한 적 없는 자막 통지는 거부된다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "COMPLETED_SCRIPT"})
	@DisplayName("의뢰 전 자막 통지는 거부된다")
	void 의뢰_전_자막_통지는_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.attachSubtitle(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	// ── fail ───────────────────────────────────────────────────────────

	/** REQ-25 · REQ-26 · REQ-134 — 실패는 진행 중 어느 상태에서든 사유와 함께 닫는다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "COMPLETED_SCRIPT", "REQUEST_SUBTITLE"})
	@DisplayName("실패는 진행 중 어느 상태에서든 사유와 함께 닫는다")
	void 실패는_진행_중_어느_상태에서든_사유와_함께_닫는다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.fail(FailureCause.ABANDONED, LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.FAILURE);
		assertThat(job.getFailureCause()).isEqualTo(FailureCause.ABANDONED);
	}

	/** REQ-139 — 이미 건넨 결과를 되뺏지 않는다 */
	@Test
	@DisplayName("완료된 작업은 실패로 가지 않는다")
	void 완료된_작업은_실패로_가지_않는다() {
		Job job = jobAt(JobStatus.COMPLETED_SUBTITLE);

		assertThatThrownBy(() -> job.fail(FailureCause.SERVER_FAULT, LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	/** REQ-70 — 실패 재호출이 조용히 두 번째 보상을 만들면 안 된다 — 무시된다 */
	@Test
	@DisplayName("실패 재호출은 무시되고 첫 사유가 남는다")
	void 실패_재호출은_무시된다() {
		Job job = jobAt(JobStatus.FAILURE);

		boolean advanced = job.fail(FailureCause.ABANDONED, LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getFailureCause()).isEqualTo(FailureCause.SERVER_FAULT);
	}

	// ── stalled / abandoned ────────────────────────────────────────────

	/** REQ-164 — 임계를 '넘겨야' 멈춘 것이다. 정확히 30분은 아직 아니다 */
	@Test
	@DisplayName("시스템 구간은 임계를 넘겨야 멈춘 것이다 — 경계 포함 안 함")
	void 시스템_구간은_임계를_넘겨야_멈춘_것이다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		assertThat(job.stalled(NOW.plus(Job.STALL_THRESHOLD), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.stalled(NOW.plus(Job.STALL_THRESHOLD).plusSeconds(1), Job.STALL_THRESHOLD)).isTrue();
	}

	/** REQ-89 · REQ-143 · REQ-146 · REQ-160 — 사용자 대기는 멈춘 것이 아니다. 섞으면 대기 작업을 계속 다시 돌린다 */
	@Test
	@DisplayName("사용자 대기 구간은 멈춤 판정 대상이 아니다")
	void 사용자_대기_구간은_멈춤_판정_대상이_아니다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		assertThat(job.stalled(NOW.plusDays(3), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.abandoned(NOW.plusDays(3))).isTrue();
	}

	/** REQ-143 — 종결 상태는 어느 판정에도 걸리지 않는다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("종결 상태는 멈춤·방치 판정 대상이 아니다")
	void 종결_상태는_멈춤_판정_대상이_아니다(JobStatus status) {
		Job job = jobAt(status);

		assertThat(job.stalled(NOW.plusDays(3), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.abandoned(NOW.plusDays(3))).isFalse();
	}

	/** REQ-31 · REQ-146 · REQ-159 — 방치는 대기 구간(COMPLETED_SCRIPT)에서 24시간을 넘긴 것만이다 */
	@Test
	@DisplayName("방치는 대기 구간에서 상한(24h)을 넘긴 것만이다")
	void 방치는_대기_구간에서_상한을_넘긴_것만이다() {
		Job waiting = jobAt(JobStatus.COMPLETED_SCRIPT);
		Job system = jobAt(JobStatus.REQUEST_SCRIPT);

		assertThat(waiting.abandoned(NOW.plus(Job.JOB_TIMEOUT))).isFalse();
		assertThat(waiting.abandoned(NOW.plus(Job.JOB_TIMEOUT).plusSeconds(1))).isTrue();
		assertThat(system.abandoned(NOW.plusDays(3))).isFalse();
	}

	// ── redispatch / requestedStage ────────────────────────────────────

	/** REQ-167 — 재개는 한계(3회)까지만, 자기 전이라 시각을 새로 찍어 재개 창을 다시 연다 */
	@Test
	@DisplayName("재개는 한계까지만 허용되고 시각을 새로 찍는다")
	void 재개는_한계까지만_허용되고_시각을_새로_찍는다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		assertThat(job.redispatch(LATER)).isTrue();
		assertThat(job.getLastTransitionedAt()).isEqualTo(LATER);
		assertThat(job.redispatch(LATER)).isTrue();
		assertThat(job.redispatch(LATER)).isTrue();
		assertThat(job.redispatch(LATER)).isFalse();
		assertThat(job.getRedispatchCount()).isEqualTo(Job.REDISPATCH_LIMIT);
		assertThat(job.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
	}

	/** REQ-86 — 재개는 다음 단계가 아니라 멈춘 그 단계의 의뢰 사건을 다시 등록한다(v3) */
	@Test
	@DisplayName("재개는 멈춘 그 단계의 의뢰 사건을 다시 등록한다")
	void 재개는_멈춘_그_단계의_의뢰_사건을_다시_등록한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);   // 픽스처가 SCRIPT·SUBTITLE 의뢰를 하나씩 발행했다
		job.redispatch(LATER);

		jobRepository.save(job);

		assertThat(events.ofType(WorkRequested.class))
			.filteredOn(requested -> requested.stage() == WorkStage.SUBTITLE)
			.hasSize(2)
			.allMatch(requested -> requested.jobId() == job.getId().longValue());
	}

	/** REQ-147 — 자기 전이가 있는 상태는 워커 의뢰 구간(REQUEST_*)뿐이다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "COMPLETED_SCRIPT", "COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("워커 의뢰 구간 밖의 재개는 거부된다")
	void 워커_의뢰_구간_밖의_재개는_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.redispatch(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	/** 조정(reconcile)의 근거 — 지금 워커에게 시켜 둔 단계를 작업이 답한다(v3) */
	@Test
	@DisplayName("워커 의뢰 구간의 작업은 시켜 둔 단계를 답하고, 그 밖은 거부한다")
	void 시켜_둔_단계_판정() {
		assertThat(jobAt(JobStatus.REQUEST_SCRIPT).requestedStage()).isEqualTo(WorkStage.SCRIPT);
		assertThat(jobAt(JobStatus.REQUEST_SUBTITLE).requestedStage()).isEqualTo(WorkStage.SUBTITLE);
		assertThatThrownBy(() -> jobAt(JobStatus.COMPLETED_SCRIPT).requestedStage())
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	// ── ownedBy / expire ───────────────────────────────────────────────

	/** REQ-144 — 소유자는 열 때 정해지고 바꾸는 행위가 없다 */
	@Test
	@DisplayName("소유자 판정 — 만든 사용자만 참이다")
	void 소유자_판정() {
		Job job = jobAt(JobStatus.CREATED);

		assertThat(job.ownedBy(JobFixture.OWNER)).isTrue();
		assertThat(job.ownedBy(JobFixture.OTHER)).isFalse();
	}

	/** REQ-99 · REQ-100 · REQ-141 — 만료는 상태가 아니라 별도 축이다. 완료·실패 어느 쪽도 만료될 수 있다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("만료는 상태를 두지 않고 시각만 기록한다 — 재만료는 무시된다")
	void 만료는_상태를_두지_않고_시각만_기록한다(JobStatus status) {
		Job job = jobAt(status);

		assertThat(job.expire(LATER)).isTrue();
		assertThat(job.getStatus()).isEqualTo(status);
		assertThat(job.expired()).isTrue();
		assertThat(job.getExpiredAt()).isEqualTo(LATER);

		assertThat(job.expire(LATER.plusDays(1))).isFalse();
		assertThat(job.getExpiredAt()).isEqualTo(LATER);
	}
}
