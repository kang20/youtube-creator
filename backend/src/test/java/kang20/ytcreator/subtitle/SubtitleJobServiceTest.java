package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
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
 * 사용자 흐름({@code SubtitleJobPort}) — 열기·수신 확인·확정·조회·목록
 * (subtitle-v1 작업 규칙 · 이용 게이트 · 작업 소유권 · 작업 목록).
 *
 * <p>DB 는 진짜(H2)를 쓴다 — 열기와 소모가 <b>한 트랜잭션</b>이라는 규칙(REQ-108)은 롤백이
 * 실제로 일어나야 검증된다. 결제·저장소·큐·링크는 전부 이연된 어댑터라 대역으로 채운다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubtitleTestClock.class})
class SubtitleJobServiceTest {

	private static final LocalDateTime NOW = SubtitleTestClock.BASE;
	private static final String UPLOAD_URL = "https://storage.example/upload?sig=abc";
	private static final String EDIT_URL = "https://storage.example/script?sig=writable";
	private static final String DOWNLOAD_URL = "https://storage.example/subtitle?sig=readonly";

	@Autowired
	private SubtitleJobPort subtitleJobPort;

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
		return JobFixture.jobAt(status, jobRepository, JobFixture.OWNER, NOW);
	}

	private static String ref(Job job) {
		return String.valueOf(job.getId().longValue());
	}

	// ── open ───────────────────────────────────────────────────────────

	/** REQ-6 · REQ-10 · REQ-12 · REQ-107 · REQ-109 · REQ-179 · REQ-180 — 자격 판정과 소모는 consume 한 호출이다 */
	@Test
	@DisplayName("작업 열기는 소모와 한 호출로 묶여 업로드 링크를 돌려준다")
	void 작업_열기는_소모와_한_호출로_묶여_업로드_링크를_돌려준다() {
		when(signedUrlIssuer.issue(any(), eq(true))).thenReturn(UPLOAD_URL);

		JobOpened opened = subtitleJobPort.open(JobFixture.OWNER);

		assertThat(opened.uploadUrl()).isEqualTo(UPLOAD_URL);
		Job saved = jobRepository.findById(new JobId(opened.jobId())).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(JobStatus.CREATED);

		verify(paymentUsagePort).consume(JobFixture.OWNER, String.valueOf(opened.jobId()));
		verifyNoMoreInteractions(paymentUsagePort);   // 묻는 것과 쓰는 것을 갈라 부르지 않는다
		verify(signedUrlIssuer).issue(StorageKey.sourceOf(saved.getId()), true);
	}

	/** REQ-11 · REQ-65 · REQ-71 · REQ-72 · REQ-108 · REQ-178 · REQ-189 — 소모 거부 = 작업도 태어나지 않는다(롤백) */
	@Test
	@DisplayName("소모가 거부되면 작업도 태어나지 않는다 — 결제 계열의 코드가 그대로 답이다")
	void 소모가_거부되면_작업도_태어나지_않는다() {
		doThrow(new BusinessException(ErrorCode.PAY_001))
			.when(paymentUsagePort).consume(any(UserId.class), any());

		assertThatThrownBy(() -> subtitleJobPort.open(JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_001);

		assertThat(jobRepository.count()).isZero();
	}

	// ── receiveSource ──────────────────────────────────────────────────

	/** REQ-13 · REQ-14 · REQ-73 · REQ-76 · REQ-113 · REQ-116 — 실물 확인 뒤 전이하고 워커에 의뢰한다 */
	@Test
	@DisplayName("원본 실물이 확인되면 대본 생성을 의뢰한다 — 소모를 다시 판정하지 않는다")
	void 원본_실물이_확인되면_대본_생성을_의뢰한다() {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(StorageKey.sourceOf(job.getId()))).thenReturn(true);

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		Job saved = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(saved.getSource()).isEqualTo(StorageKey.sourceOf(job.getId()));

		verify(workDispatcher).dispatch(job.getId(), JobStatus.REQUEST_SCRIPT);
		verifyNoInteractions(paymentUsagePort);   // 진행 경로에서 이용권을 다시 묻지 않는다
	}

	/** REQ-13 · REQ-114 — 클라이언트의 "다 올렸다"만으로 착수하지 않는다 */
	@Test
	@DisplayName("원본 실물이 없으면 전이하지 않는다 — 소모도 일어나지 않는다")
	void 원본_실물이_없으면_전이하지_않는다() {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(any())).thenReturn(false);

		assertThatThrownBy(() -> subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CREATED);
		verifyNoInteractions(workDispatcher, paymentUsagePort);
	}

	/** REQ-13 · REQ-177 — 확인에 실패하면 전이하지 않는다. 장애를 삼키고 믿으면 서버 확인이 무너진다 */
	@Test
	@DisplayName("저장소 확인 실패는 삼키지 않고 전이도 하지 않는다")
	void 저장소_확인_실패는_삼키지_않고_전이도_하지_않는다() {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(any())).thenThrow(new IllegalStateException("storage unavailable"));

		assertThatThrownBy(() -> subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER))
			.isInstanceOf(IllegalStateException.class);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CREATED);
		verifyNoInteractions(workDispatcher);
	}

	/** REQ-140 — 재요청은 현재 상태를 돌려주고, 다시 의뢰하지도 시각을 갱신하지도 않는다 */
	@Test
	@DisplayName("원본 수신 재요청은 다시 의뢰하지 않는다")
	void 원본_수신_재요청은_다시_의뢰하지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		when(storageInspector.exists(any())).thenReturn(true);

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getLastTransitionedAt()).isEqualTo(NOW);
		verifyNoInteractions(workDispatcher);
	}

	/** REQ-91 · REQ-92 — 남의 작업은 저장소 확인 결과로 답이 갈리기 전에 끊긴다 — 존재를 알려주지 않는다 */
	@Test
	@DisplayName("남의 작업은 저장소를 확인하기 전에 없는 작업과 같은 답으로 끊긴다")
	void 남의_작업은_저장소를_확인하기_전에_없는_작업과_같은_답으로_끊긴다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> subtitleJobPort.receiveSource(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);

		verifyNoInteractions(storageInspector);
	}

	/** REQ-15 · REQ-76 · REQ-85 · REQ-170 — 의뢰 유실은 오류가 아니라 "상태가 나아가지 않음"으로 드러난다 */
	@Test
	@DisplayName("의뢰 실패는 응답을 막지 않고 상태가 진실로 남는다")
	void 의뢰_실패는_응답을_막지_않고_상태가_진실로_남는다() {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(any())).thenReturn(true);
		doThrow(new IllegalStateException("queue unavailable"))
			.when(workDispatcher).dispatch(any(), any());

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
			.isEqualTo(JobStatus.REQUEST_SCRIPT);   // 재개 배치가 이 상태를 보고 다시 넘긴다
	}

	// ── confirmScript ──────────────────────────────────────────────────

	/** REQ-19 · REQ-21 · REQ-51 · REQ-52 · REQ-78 · REQ-122 · REQ-123 · REQ-124 · REQ-126 · REQ-175 */
	@Test
	@DisplayName("확정은 빈 대본 판정을 우리가 하고 자막 산출을 의뢰한다")
	void 확정은_빈_대본_판정을_우리가_하고_자막_산출을_의뢰한다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		when(storageInspector.scriptEmpty(JobFixture.SCRIPT_KEY)).thenReturn(false);

		JobStatus status = subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		verify(storageInspector).scriptEmpty(JobFixture.SCRIPT_KEY);
		verify(workDispatcher).dispatch(job.getId(), JobStatus.REQUEST_SUBTITLE);
		verify(paymentUsagePort, never()).commit(any());

		Job saved = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(saved.getScript()).isEqualTo(JobFixture.SCRIPT_KEY);   // 확정 대본 위치가 남는다
	}

	/** REQ-42 · REQ-131 — 빈 대본은 워커 없이 완료로 가고, 그 완료도 소모가 확정되는 사건이다 */
	@Test
	@DisplayName("빈 대본 확정은 의뢰 없이 완료로 가고 소모를 확정한다")
	void 빈_대본_확정은_의뢰_없이_완료로_가고_소모를_확정한다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		when(storageInspector.scriptEmpty(any())).thenReturn(true);

		JobStatus status = subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		verify(paymentUsagePort).commit(ref(job));
		verifyNoInteractions(workDispatcher);
	}

	/** REQ-53 · REQ-138 — 재확정은 판정할 것도 의뢰할 것도 없다. 산출이 두 번 돌면 파일이 두 벌 생긴다 */
	@Test
	@DisplayName("확정 재요청은 판정도 의뢰도 다시 하지 않는다")
	void 확정_재요청은_판정도_의뢰도_다시_하지_않는다() {
		Job requested = jobAt(JobStatus.REQUEST_SUBTITLE);
		Job completed = jobAt(JobStatus.COMPLETED_SUBTITLE);

		assertThat(subtitleJobPort.confirmScript(requested.getId(), JobFixture.OWNER))
			.isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(subtitleJobPort.confirmScript(completed.getId(), JobFixture.OWNER))
			.isEqualTo(JobStatus.COMPLETED_SUBTITLE);

		verifyNoInteractions(storageInspector, workDispatcher);
		verify(paymentUsagePort, never()).commit(any());   // 완료 재확정이 소모 확정을 반복하면 안 된다
	}

	/** REQ-137 — 확정은 사용자 대기 구간에서만 받는다 */
	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "FAILURE"})
	@DisplayName("대기 구간 밖의 확정은 거부된다")
	void 대기_구간_밖의_확정은_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	// ── detail ─────────────────────────────────────────────────────────

	/** REQ-16 · REQ-18 · REQ-22 · REQ-50 · REQ-83 · REQ-120 · REQ-155 — 편집 링크는 대기 구간에서만 열린다 */
	@Test
	@DisplayName("조회는 대기 구간에서만 대본 편집 링크를 연다 — 다른 상태는 상태만 답한다")
	void 조회는_대기_구간에서만_대본_편집_링크를_연다() {
		when(signedUrlIssuer.issue(JobFixture.SCRIPT_KEY, true)).thenReturn(EDIT_URL);
		Job waiting = jobAt(JobStatus.COMPLETED_SCRIPT);

		JobDetail waitingDetail = subtitleJobPort.detail(waiting.getId(), JobFixture.OWNER);
		assertThat(waitingDetail.status()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(waitingDetail.scriptUrl()).isEqualTo(EDIT_URL);
		assertThat(waitingDetail.scriptUrl()).doesNotContain(JobFixture.SCRIPT_KEY.value());
		assertThat(waitingDetail.subtitleUrl()).isNull();

		for (JobStatus status : new JobStatus[] {JobStatus.CREATED, JobStatus.REQUEST_SCRIPT}) {
			Job job = jobAt(status);
			JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);
			assertThat(detail.status()).isEqualTo(status);
			assertThat(detail.scriptUrl()).isNull();
			assertThat(detail.subtitleUrl()).isNull();
		}
	}

	/** REQ-54 · REQ-125 — 확정되면 대본 수정 접근 권한을 막는다 — 쓰기 링크가 다시 열리지 않는다 */
	@Test
	@DisplayName("확정 뒤에는 편집 링크가 다시 열리지 않는다")
	void 확정_뒤에는_편집_링크가_다시_열리지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.scriptUrl()).isNull();
		verifyNoInteractions(signedUrlIssuer);
	}

	/** REQ-35 · REQ-64 — 완료 결과는 읽기 링크와 형식으로 받는다. 만료돼도 이용권 없이도 그대로다 */
	@Test
	@DisplayName("완료 조회는 자막 링크와 형식을 준다 — 만료돼도, 이용권이 없어도")
	void 완료_조회는_자막_링크와_형식을_준다() {
		doThrow(new BusinessException(ErrorCode.PAY_001))
			.when(paymentUsagePort).consume(any(UserId.class), any());   // 이용권 전부 거부 상태
		when(signedUrlIssuer.issue(JobFixture.SUBTITLE_KEY, false)).thenReturn(DOWNLOAD_URL);
		Job job = jobAt(JobStatus.COMPLETED_SUBTITLE);
		job.expire(NOW.plusMonths(1));
		jobRepository.save(job);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.status()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(detail.subtitleUrl()).isEqualTo(DOWNLOAD_URL);
		assertThat(detail.format()).isEqualTo(SubtitleFileFormat.MARKDOWN);
		assertThat(detail.scriptUrl()).isNull();
		assertThat(detail.expired()).isTrue();
		verify(signedUrlIssuer).issue(JobFixture.SUBTITLE_KEY, false);
		verify(paymentUsagePort, never()).consume(any(UserId.class), any());
	}

	/** REQ-42 — 빈 대본 건너뜀 완료는 받을 자막 실물이 없다 — 완료 상태만 답한다 */
	@Test
	@DisplayName("빈 대본 완료 조회는 자막 링크 없이 완료만 답한다")
	void 빈_대본_완료_조회는_자막_링크_없이_완료만_답한다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		when(storageInspector.scriptEmpty(any())).thenReturn(true);
		subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.status()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(detail.subtitleUrl()).isNull();
		assertThat(detail.format()).isNull();
		verifyNoInteractions(signedUrlIssuer);
	}

	/** REQ-75 · REQ-190 — 처리 실패는 오류 응답이 아니라 상태와 사유다 */
	@Test
	@DisplayName("실패한 작업 조회는 오류가 아니라 상태와 사유를 답한다")
	void 실패한_작업_조회는_오류가_아니라_상태와_사유를_답한다() {
		Job job = jobAt(JobStatus.FAILURE);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.status()).isEqualTo(JobStatus.FAILURE);
		assertThat(detail.failureCause()).isEqualTo(FailureCause.SERVER_FAULT);
	}

	/** REQ-77 · REQ-140 — 조회는 저장된 상태만 답한다. 시각을 갱신하면 멈춘 작업이 영원히 안 잡힌다 */
	@Test
	@DisplayName("조회는 전이 시각을 갱신하지 않고 저장소도 부르지 않는다")
	void 조회는_전이_시각을_갱신하지_않고_저장소도_부르지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getLastTransitionedAt()).isEqualTo(NOW);
		verifyNoInteractions(storageInspector, workDispatcher);
	}

	/** REQ-63 · REQ-64 — 이용 게이트는 여는 행위에만 걸린다. 조회·목록은 이용권을 묻지 않는다 */
	@Test
	@DisplayName("조회와 수령에는 이용 게이트가 걸리지 않는다")
	void 조회와_수령에는_이용_게이트가_걸리지_않는다() {
		doThrow(new BusinessException(ErrorCode.PAY_001))
			.when(paymentUsagePort).consume(any(UserId.class), any());
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		when(signedUrlIssuer.issue(any(), eq(true))).thenReturn(EDIT_URL);

		subtitleJobPort.detail(job.getId(), JobFixture.OWNER);
		subtitleJobPort.list(JobFixture.OWNER);

		verify(paymentUsagePort, never()).consume(any(UserId.class), any());
	}

	/** REQ-91 · REQ-92 · REQ-187 — 없는 작업과 남의 작업은 같은 답이다. 갈라 답하면 존재가 샌다 */
	@Test
	@DisplayName("없는 작업과 남의 작업은 같은 답이다")
	void 없는_작업과_남의_작업은_같은_답이다() {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);

		assertThatThrownBy(() -> subtitleJobPort.detail(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
		assertThatThrownBy(() -> subtitleJobPort.detail(new JobId(987654321L), JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
		assertThatThrownBy(() -> subtitleJobPort.confirmScript(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
	}

	/** REQ-144 — 소유권 방어선은 쓰기 트랜잭션 안에도 있다(심층 방어) */
	@Test
	@DisplayName("쓰기 빈도 소유권을 다시 확인한다")
	void 쓰기_빈도_소유권을_다시_확인한다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> jobWriter.receiveSource(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
	}

	// ── list ───────────────────────────────────────────────────────────

	/** REQ-95 · REQ-104 · REQ-183 · REQ-184 · REQ-185 — 목록은 복구 장치다. 만료 작업도 "만료됨"으로 남는다 */
	@Test
	@DisplayName("목록은 내 작업만 최신순으로 상태와 만료를 담는다 — 저장소를 부르지 않는다")
	void 목록은_내_작업만_최신순으로_상태와_만료를_담는다() {
		Job inProgress = jobAt(JobStatus.REQUEST_SCRIPT);
		Job expired = jobAt(JobStatus.COMPLETED_SUBTITLE);
		expired.expire(NOW.plusMonths(1));
		jobRepository.save(expired);
		JobFixture.jobAt(JobStatus.CREATED, jobRepository, JobFixture.OTHER, NOW);   // 남의 작업

		JobList list = subtitleJobPort.list(JobFixture.OWNER);

		assertThat(list.jobs()).hasSize(2);
		assertThat(list.jobs().get(0).jobId()).isEqualTo(expired.getId().longValue());   // 최신순
		assertThat(list.jobs().get(0).status()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(list.jobs().get(0).expired()).isTrue();
		assertThat(list.jobs().get(1).jobId()).isEqualTo(inProgress.getId().longValue());
		assertThat(list.jobs().get(1).status()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(list.jobs().get(1).expired()).isFalse();
		assertThat(list.jobs().get(1).createdAt()).isNotNull();

		verifyNoInteractions(signedUrlIssuer, storageInspector);
	}

	/** REQ-186 의 절반 — 작업이 없는 것은 실패가 아니라 빈 jobs 다(실패와의 모양 구분은 컨트롤러 테스트) */
	@Test
	@DisplayName("빈 목록은 실패가 아니라 빈 jobs 로 답한다")
	void 빈_목록은_실패가_아니라_빈_jobs_로_답한다() {
		JobList list = subtitleJobPort.list(new UserId(777777L));

		assertThat(list.jobs()).isNotNull().isEmpty();
	}
}
