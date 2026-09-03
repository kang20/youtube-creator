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

	@Test
	@DisplayName("열린 작업은 CREATED 로 태어나고 소유자와 전이 시각이 기록된다")
	void 열린_작업은_CREATED_로_태어난다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThat(job.getStatus()).isEqualTo(JobStatus.CREATED);
		assertThat(job.getUserId()).isEqualTo(JobFixture.OWNER);
		assertThat(job.getLastTransitionedAt()).isEqualTo(NOW);
		assertThat(job.getSource()).isNull();
	}

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

	@Test
	@DisplayName("원본 수신 확인은 SCRIPT 의뢰 사건을 등록하고 저장이 발행한다")
	void 원본_수신_확인은_SCRIPT_의뢰_사건을_등록하고_저장이_발행한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.CREATED);
		job.receiveSource(LATER);

		jobRepository.save(job);

		assertThat(events.ofType(WorkRequested.class))
			.containsExactly(WorkRequested.of(job.getId(), WorkStage.SCRIPT));
	}

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

	@Test
	@DisplayName("닫힌 작업의 원본 수신은 거부된다")
	void 닫힌_작업의_원본_수신은_거부된다() {
		Job job = jobAt(JobStatus.FAILURE);

		assertThatThrownBy(() -> job.receiveSource(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

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

	@Test
	@DisplayName("의뢰한 적 없는 대본 통지는 거부된다")
	void 의뢰한_적_없는_대본_통지는_거부된다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> job.attachScript(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

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

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"REQUEST_SUBTITLE", "COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("지난 단계·닫힌 작업의 대본 통지는 무시된다 — 상태가 되돌아가지 않는다")
	void 지난_단계의_대본_통지는_무시된다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.attachScript(LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
	}

	@Test
	@DisplayName("확정은 내용이 있으면 REQUEST_SUBTITLE 로 간다 — 머무는 확정 상태는 없다")
	void 확정은_내용이_있으면_REQUEST_SUBTITLE_로_간다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		boolean advanced = job.confirmScript(false, LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(job.getLastTransitionedAt()).isEqualTo(LATER);
	}

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

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "FAILURE"})
	@DisplayName("대기 구간 밖의 확정은 거부된다")
	void 대기_구간_밖의_확정은_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.confirmScript(false, LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	@Test
	@DisplayName("자막 통지는 작업 번호로 정해진 자막 위치를 달고 완료로 전이한다")
	void 자막_통지는_자막을_달고_완료로_전이한다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);

		boolean advanced = job.attachSubtitle(LATER);

		assertThat(advanced).isTrue();
		assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(job.getSubtitle()).isEqualTo(StorageKey.subtitleOf(job.getId()));
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("중복·닫힌 작업의 자막 통지는 무시된다")
	void 중복_자막_통지는_무시된다(JobStatus status) {
		Job job = jobAt(status);

		boolean advanced = job.attachSubtitle(LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getStatus()).isEqualTo(status);
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "COMPLETED_SCRIPT"})
	@DisplayName("의뢰 전 자막 통지는 거부된다")
	void 의뢰_전_자막_통지는_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.attachSubtitle(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

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

	@Test
	@DisplayName("완료된 작업은 실패로 가지 않는다")
	void 완료된_작업은_실패로_가지_않는다() {
		Job job = jobAt(JobStatus.COMPLETED_SUBTITLE);

		assertThatThrownBy(() -> job.fail(FailureCause.SERVER_FAULT, LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	@Test
	@DisplayName("실패 재호출은 무시되고 첫 사유가 남는다")
	void 실패_재호출은_무시된다() {
		Job job = jobAt(JobStatus.FAILURE);

		boolean advanced = job.fail(FailureCause.ABANDONED, LATER);

		assertThat(advanced).isFalse();
		assertThat(job.getFailureCause()).isEqualTo(FailureCause.SERVER_FAULT);
	}

	@Test
	@DisplayName("시스템 구간은 임계를 넘겨야 멈춘 것이다 — 경계 포함 안 함")
	void 시스템_구간은_임계를_넘겨야_멈춘_것이다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		assertThat(job.stalled(NOW.plus(Job.STALL_THRESHOLD), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.stalled(NOW.plus(Job.STALL_THRESHOLD).plusSeconds(1), Job.STALL_THRESHOLD)).isTrue();
	}

	@Test
	@DisplayName("사용자 대기 구간은 멈춤 판정 대상이 아니다")
	void 사용자_대기_구간은_멈춤_판정_대상이_아니다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		assertThat(job.stalled(NOW.plusDays(3), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.abandoned(NOW.plusDays(3))).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("종결 상태는 멈춤·방치 판정 대상이 아니다")
	void 종결_상태는_멈춤_판정_대상이_아니다(JobStatus status) {
		Job job = jobAt(status);

		assertThat(job.stalled(NOW.plusDays(3), Job.STALL_THRESHOLD)).isFalse();
		assertThat(job.abandoned(NOW.plusDays(3))).isFalse();
	}

	@Test
	@DisplayName("방치는 대기 구간에서 상한(24h)을 넘긴 것만이다")
	void 방치는_대기_구간에서_상한을_넘긴_것만이다() {
		Job waiting = jobAt(JobStatus.COMPLETED_SCRIPT);
		Job system = jobAt(JobStatus.REQUEST_SCRIPT);

		assertThat(waiting.abandoned(NOW.plus(Job.JOB_TIMEOUT))).isFalse();
		assertThat(waiting.abandoned(NOW.plus(Job.JOB_TIMEOUT).plusSeconds(1))).isTrue();
		assertThat(system.abandoned(NOW.plusDays(3))).isFalse();
	}

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

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "COMPLETED_SCRIPT", "COMPLETED_SUBTITLE", "FAILURE"})
	@DisplayName("워커 의뢰 구간 밖의 재개는 거부된다")
	void 워커_의뢰_구간_밖의_재개는_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> job.redispatch(LATER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	@Test
	@DisplayName("워커 의뢰 구간의 작업은 시켜 둔 단계를 답하고, 그 밖은 거부한다")
	void 시켜_둔_단계_판정() {
		assertThat(jobAt(JobStatus.REQUEST_SCRIPT).requestedStage()).isEqualTo(WorkStage.SCRIPT);
		assertThat(jobAt(JobStatus.REQUEST_SUBTITLE).requestedStage()).isEqualTo(WorkStage.SUBTITLE);
		assertThatThrownBy(() -> jobAt(JobStatus.COMPLETED_SCRIPT).requestedStage())
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	@Test
	@DisplayName("소유자 판정 — 만든 사용자만 참이다")
	void 소유자_판정() {
		Job job = jobAt(JobStatus.CREATED);

		assertThat(job.ownedBy(JobFixture.OWNER)).isTrue();
		assertThat(job.ownedBy(JobFixture.OTHER)).isFalse();
	}

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
