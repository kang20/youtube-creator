package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
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
import org.springframework.modulith.test.PublishedEvents;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
		return JobFixture.jobAt(status, jobRepository, JobFixture.OWNER, NOW, workDispatcher);
	}

	private static String ref(Job job) {
		return String.valueOf(job.getId().longValue());
	}

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

	@Test
	@DisplayName("원본 실물이 확인되면 대본 생성 의뢰가 발행되고 큐로 넘어간다 — 소모를 다시 판정하지 않는다")
	void 원본_실물이_확인되면_대본_생성을_의뢰한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(StorageKey.sourceOf(job.getId()))).thenReturn(true);

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		Job saved = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(saved.getSource()).isEqualTo(StorageKey.sourceOf(job.getId()));

		assertThat(events.ofType(WorkRequested.class))
			.containsExactly(WorkRequested.of(job.getId(), WorkStage.SCRIPT));
		verify(workDispatcher, timeout(5000)).dispatch(job.getId(), WorkStage.SCRIPT);
		verifyNoInteractions(paymentUsagePort);   // 진행 경로에서 이용권을 다시 묻지 않는다
	}

	@Test
	@DisplayName("원본 실물이 없으면 전이하지 않는다 — 의뢰도 소모도 일어나지 않는다")
	void 원본_실물이_없으면_전이하지_않는다(PublishedEvents events) {
		Job job = jobAt(JobStatus.CREATED);
		when(storageInspector.exists(any())).thenReturn(false);

		assertThatThrownBy(() -> subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.CREATED);
		assertThat(events.ofType(WorkRequested.class)).isEmpty();
		verifyNoInteractions(workDispatcher, paymentUsagePort);
	}

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

	@Test
	@DisplayName("원본 수신 재요청은 다시 의뢰하지 않는다")
	void 원본_수신_재요청은_다시_의뢰하지_않는다(PublishedEvents events) {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);
		when(storageInspector.exists(any())).thenReturn(true);

		JobStatus status = subtitleJobPort.receiveSource(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SCRIPT);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getLastTransitionedAt()).isEqualTo(NOW);
		assertThat(events.ofType(WorkRequested.class)).hasSize(1);   // 픽스처의 첫 의뢰뿐이다
		verifyNoInteractions(workDispatcher);
	}

	@Test
	@DisplayName("남의 작업은 저장소를 확인하기 전에 없는 작업과 같은 답으로 끊긴다")
	void 남의_작업은_저장소를_확인하기_전에_없는_작업과_같은_답으로_끊긴다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> subtitleJobPort.receiveSource(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);

		verifyNoInteractions(storageInspector);
	}

	@Test
	@DisplayName("확정은 빈 대본 판정을 우리가 하고 자막 산출 의뢰를 발행한다")
	void 확정은_빈_대본_판정을_우리가_하고_자막_산출을_의뢰한다(PublishedEvents events) {
		Job job = jobAt(JobStatus.COMPLETED_SCRIPT);
		StorageKey script = StorageKey.scriptOf(job.getId());
		when(storageInspector.scriptEmpty(script)).thenReturn(false);

		JobStatus status = subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER);

		assertThat(status).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		verify(storageInspector).scriptEmpty(script);
		assertThat(events.ofType(WorkRequested.class))
			.filteredOn(requested -> requested.stage() == WorkStage.SUBTITLE)
			.containsExactly(WorkRequested.of(job.getId(), WorkStage.SUBTITLE));
		verify(workDispatcher, timeout(5000)).dispatch(job.getId(), WorkStage.SUBTITLE);
		verify(paymentUsagePort, never()).commit(any());

		Job saved = jobRepository.findById(job.getId()).orElseThrow();
		assertThat(saved.getStatus()).isEqualTo(JobStatus.REQUEST_SUBTITLE);
		assertThat(saved.getScript()).isEqualTo(script);   // 확정 대본 위치가 남는다
	}

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

	@ParameterizedTest
	@EnumSource(value = JobStatus.class, names = {"CREATED", "REQUEST_SCRIPT", "FAILURE"})
	@DisplayName("대기 구간 밖의 확정은 거부된다")
	void 대기_구간_밖의_확정은_거부된다(JobStatus status) {
		Job job = jobAt(status);

		assertThatThrownBy(() -> subtitleJobPort.confirmScript(job.getId(), JobFixture.OWNER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_002);
	}

	@Test
	@DisplayName("조회는 대기 구간에서만 대본 편집 링크를 연다 — 다른 상태는 상태만 답한다")
	void 조회는_대기_구간에서만_대본_편집_링크를_연다() {
		Job waiting = jobAt(JobStatus.COMPLETED_SCRIPT);
		StorageKey script = StorageKey.scriptOf(waiting.getId());
		when(signedUrlIssuer.issue(script, true)).thenReturn(EDIT_URL);

		JobDetail waitingDetail = subtitleJobPort.detail(waiting.getId(), JobFixture.OWNER);
		assertThat(waitingDetail.status()).isEqualTo(JobStatus.COMPLETED_SCRIPT);
		assertThat(waitingDetail.scriptUrl()).isEqualTo(EDIT_URL);
		assertThat(waitingDetail.scriptUrl()).doesNotContain(script.value());
		assertThat(waitingDetail.subtitleUrl()).isNull();

		for (JobStatus status : new JobStatus[] {JobStatus.CREATED, JobStatus.REQUEST_SCRIPT}) {
			Job job = jobAt(status);
			JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);
			assertThat(detail.status()).isEqualTo(status);
			assertThat(detail.scriptUrl()).isNull();
			assertThat(detail.subtitleUrl()).isNull();
		}
	}

	@Test
	@DisplayName("확정 뒤에는 편집 링크가 다시 열리지 않는다")
	void 확정_뒤에는_편집_링크가_다시_열리지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SUBTITLE);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.scriptUrl()).isNull();
		verifyNoInteractions(signedUrlIssuer);
	}

	@Test
	@DisplayName("완료 조회는 자막 링크와 형식을 준다 — 만료돼도, 이용권이 없어도")
	void 완료_조회는_자막_링크와_형식을_준다() {
		doThrow(new BusinessException(ErrorCode.PAY_001))
			.when(paymentUsagePort).consume(any(UserId.class), any());   // 이용권 전부 거부 상태
		Job job = jobAt(JobStatus.COMPLETED_SUBTITLE);
		StorageKey subtitle = StorageKey.subtitleOf(job.getId());
		when(signedUrlIssuer.issue(subtitle, false)).thenReturn(DOWNLOAD_URL);
		job.expire(NOW.plusMonths(1));
		jobRepository.save(job);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.status()).isEqualTo(JobStatus.COMPLETED_SUBTITLE);
		assertThat(detail.subtitleUrl()).isEqualTo(DOWNLOAD_URL);
		assertThat(detail.format()).isEqualTo(SubtitleFileFormat.MARKDOWN);
		assertThat(detail.scriptUrl()).isNull();
		assertThat(detail.expired()).isTrue();
		verify(signedUrlIssuer).issue(subtitle, false);
		verify(paymentUsagePort, never()).consume(any(UserId.class), any());
	}

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

	@Test
	@DisplayName("실패한 작업 조회는 오류가 아니라 상태와 사유를 답한다")
	void 실패한_작업_조회는_오류가_아니라_상태와_사유를_답한다() {
		Job job = jobAt(JobStatus.FAILURE);

		JobDetail detail = subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(detail.status()).isEqualTo(JobStatus.FAILURE);
		assertThat(detail.failureCause()).isEqualTo(FailureCause.SERVER_FAULT);
	}

	@Test
	@DisplayName("조회는 전이 시각을 갱신하지 않고 저장소도 부르지 않는다")
	void 조회는_전이_시각을_갱신하지_않고_저장소도_부르지_않는다() {
		Job job = jobAt(JobStatus.REQUEST_SCRIPT);

		subtitleJobPort.detail(job.getId(), JobFixture.OWNER);

		assertThat(jobRepository.findById(job.getId()).orElseThrow().getLastTransitionedAt()).isEqualTo(NOW);
		verifyNoInteractions(storageInspector, workDispatcher);
	}

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

	@Test
	@DisplayName("쓰기 빈도 소유권을 다시 확인한다")
	void 쓰기_빈도_소유권을_다시_확인한다() {
		Job job = jobAt(JobStatus.CREATED);

		assertThatThrownBy(() -> jobWriter.receiveSource(job.getId(), JobFixture.OTHER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.SUBTITLE_001);
	}

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

	@Test
	@DisplayName("빈 목록은 실패가 아니라 빈 jobs 로 답한다")
	void 빈_목록은_실패가_아니라_빈_jobs_로_답한다() {
		JobList list = subtitleJobPort.list(new UserId(777777L));

		assertThat(list.jobs()).isNotNull().isEmpty();
	}
}
