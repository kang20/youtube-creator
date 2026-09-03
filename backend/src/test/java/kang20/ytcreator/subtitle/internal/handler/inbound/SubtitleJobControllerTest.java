package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.payload.JsonFieldType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SubtitleJobController.class)
class SubtitleJobControllerTest extends ControllerTest {

	private static final UserId USER_ID = new UserId(1L);
	private static final long JOB_ID = 5L;
	private static final JobId JOB = new JobId(JOB_ID);

	private static final String UPLOAD_URL = "https://storage.example.com/jobs/5/source?sig=upload";
	private static final String EDIT_URL = "https://storage.example.com/jobs/5/script?sig=edit";
	private static final String DOWNLOAD_URL = "https://storage.example.com/jobs/5/subtitle?sig=read";

	@MockitoBean
	private SubtitleJobPort subtitleJobPort;

	@Test
	@DisplayName("열기는 200 으로 작업 번호와 업로드 링크를 답한다")
	void 열기() throws Exception {
		when(subtitleJobPort.open(USER_ID)).thenReturn(new JobOpened(JOB_ID, UPLOAD_URL));

		mockMvc.perform(post("/api/v1/jobs")
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobId").value(JOB_ID))
			.andExpect(jsonPath("$.uploadUrl").value(UPLOAD_URL))
			.andDo(document("subtitle-open",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION)
					.description("Bearer access 토큰 — 이 토큰의 사용자가 작업의 소유자가 된다")),
				responseFields(
					fieldWithPath("jobId").description("작업 번호 — 이후 모든 요청의 경로 변수"),
					fieldWithPath("uploadUrl").description("원본 업로드용 단명 링크. 업로드가 끝나면"
						+ " `POST /api/v1/jobs/{jobId}/source` 로 수신 확인을 요청한다"))));
	}

	@Test
	@DisplayName("이용권이 없으면 403 PAY_001 이다 — 결제 계열의 코드가 그대로 온다")
	void 열기_이용권_없음() throws Exception {
		when(subtitleJobPort.open(USER_ID)).thenThrow(new BusinessException(ErrorCode.PAY_001));

		expectError(post("/api/v1/jobs"), status().isForbidden(), ErrorCode.PAY_001,
			"subtitle-open-fail-no-pass",
			"PAY_001 — 이용 가능한 이용권이 없다. 결제 화면으로 유도한다. 작업은 태어나지 않았다");
	}

	@Test
	@DisplayName("원본 수신 확인은 200 으로 다음 상태를 답한다 — 재요청도 같은 응답이다")
	void 원본_수신() throws Exception {
		when(subtitleJobPort.receiveSource(eq(JOB), eq(USER_ID))).thenReturn(JobStatus.REQUEST_SCRIPT);

		mockMvc.perform(post("/api/v1/jobs/{jobId}/source", JOB_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REQUEST_SCRIPT"))
			.andDo(document("subtitle-receive-source",
				requestPreprocessor(), responsePreprocessor(),
				pathParameters(parameterWithName("jobId").description("열기 응답의 작업 번호")),
				responseFields(fieldWithPath("status")
					.description("전이된 작업 상태. 이후 화면 갱신은 상태 조회(폴링)로 한다"))));
	}

	@Test
	@DisplayName("원본 실물이 확인되지 않으면 409 SUBTITLE_002 다")
	void 원본_수신_업로드_미확인() throws Exception {
		when(subtitleJobPort.receiveSource(eq(JOB), eq(USER_ID)))
			.thenThrow(new BusinessException(ErrorCode.SUBTITLE_002));

		expectError(post("/api/v1/jobs/{jobId}/source", JOB_ID), status().isConflict(), ErrorCode.SUBTITLE_002,
			"subtitle-receive-source-fail-not-uploaded",
			"SUBTITLE_002 — 지금 상태에서는 처리할 수 없다(원본 미확인·닫힌 작업). 업로드를 마친 뒤 다시 요청한다");
	}

	@Test
	@DisplayName("없는 작업·남의 작업은 404 SUBTITLE_001 하나다")
	void 원본_수신_없는_작업() throws Exception {
		when(subtitleJobPort.receiveSource(eq(JOB), eq(USER_ID)))
			.thenThrow(new BusinessException(ErrorCode.SUBTITLE_001));

		expectError(post("/api/v1/jobs/{jobId}/source", JOB_ID), status().isNotFound(), ErrorCode.SUBTITLE_001,
			"subtitle-receive-source-fail-not-found",
			"SUBTITLE_001 — 작업을 찾을 수 없다. 남의 작업도 같은 답이다(존재 비노출)");
	}

	@Test
	@DisplayName("확정은 200 으로 다음 상태를 답한다 — 재요청도 같은 모양이다(멱등)")
	void 확정() throws Exception {
		when(subtitleJobPort.confirmScript(eq(JOB), eq(USER_ID))).thenReturn(JobStatus.REQUEST_SUBTITLE);

		mockMvc.perform(post("/api/v1/jobs/{jobId}/confirm", JOB_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REQUEST_SUBTITLE"))
			.andDo(document("subtitle-confirm",
				requestPreprocessor(), responsePreprocessor(),
				pathParameters(parameterWithName("jobId").description("작업 번호")),
				responseFields(fieldWithPath("status")
					.description("전이된 작업 상태 — REQUEST_SUBTITLE(산출 의뢰) 또는"
						+ " COMPLETED_SUBTITLE(빈 대본 건너뜀 완료). 재요청이면 현재 상태 그대로"))));
	}

	@Test
	@DisplayName("대기 구간 밖의 확정은 409 SUBTITLE_002 다")
	void 확정_대기_구간_밖() throws Exception {
		when(subtitleJobPort.confirmScript(eq(JOB), eq(USER_ID)))
			.thenThrow(new BusinessException(ErrorCode.SUBTITLE_002));

		expectError(post("/api/v1/jobs/{jobId}/confirm", JOB_ID), status().isConflict(), ErrorCode.SUBTITLE_002,
			"subtitle-confirm-fail-not-editable",
			"SUBTITLE_002 — 대본 확정을 기다리는 상태가 아니다. 상태를 조회해 화면을 갱신한다");
	}

	@Test
	@DisplayName("조회는 200 으로 현재 상태를 답한다 — 대본 확정 대기면 편집 링크가 함께 온다")
	void 조회_대기() throws Exception {
		when(subtitleJobPort.detail(eq(JOB), eq(USER_ID))).thenReturn(new JobDetail(
			JOB_ID, JobStatus.COMPLETED_SCRIPT, null, false, EDIT_URL, null, null));

		mockMvc.perform(get("/api/v1/jobs/{jobId}", JOB_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED_SCRIPT"))
			.andExpect(jsonPath("$.scriptUrl").value(EDIT_URL))
			.andDo(document("subtitle-detail",
				requestPreprocessor(), responsePreprocessor(),
				pathParameters(parameterWithName("jobId").description("작업 번호")),
				responseFields(
					fieldWithPath("jobId").description("작업 번호"),
					fieldWithPath("status").description("작업 진행 상태 — 화면 분기의 유일한 근거."
						+ " CREATED · REQUEST_SCRIPT · COMPLETED_SCRIPT ·"
						+ " REQUEST_SUBTITLE · COMPLETED_SUBTITLE · FAILURE"),
					fieldWithPath("failureCause").type(JsonFieldType.STRING).optional()
						.description("FAILURE 일 때만. SERVER_FAULT(서버 귀책 — 이용권 회복됨) ·"
							+ " ABANDONED(방치 — 회복 없음)"),
					fieldWithPath("expired").description("원본 보관 기간 경과 여부 — 상태와 별도 축."
						+ " 완료·실패와 무관하게 참일 수 있다"),
					fieldWithPath("scriptUrl").type(JsonFieldType.STRING).optional()
						.description("대본 편집용 단명 링크(쓰기 가능). COMPLETED_SCRIPT 에서만 온다 —"
							+ " 확정하면 다시 열리지 않는다"),
					fieldWithPath("subtitleUrl").type(JsonFieldType.STRING).optional()
						.description("자막 파일 단명 링크(읽기). COMPLETED_SUBTITLE 에서만 온다"),
					fieldWithPath("format").type(JsonFieldType.STRING).optional()
						.description("자막 파일 형식 — 지금은 MARKDOWN 하나. subtitleUrl 이 있을 때만 온다"))));
	}

	@Test
	@DisplayName("완료된 작업 조회는 자막 링크와 형식을 준다")
	void 조회_완료() throws Exception {
		when(subtitleJobPort.detail(eq(JOB), eq(USER_ID))).thenReturn(new JobDetail(
			JOB_ID, JobStatus.COMPLETED_SUBTITLE, null, false, null, DOWNLOAD_URL, SubtitleFileFormat.MARKDOWN));

		mockMvc.perform(get("/api/v1/jobs/{jobId}", JOB_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED_SUBTITLE"))
			.andExpect(jsonPath("$.subtitleUrl").value(DOWNLOAD_URL))
			.andExpect(jsonPath("$.format").value("MARKDOWN"))
			.andDo(document("subtitle-detail-completed",
				requestPreprocessor(), responsePreprocessor()));
	}

	@Test
	@DisplayName("실패한 작업 조회는 오류가 아니라 200 으로 상태와 사유를 답한다")
	void 조회_실패한_작업() throws Exception {
		when(subtitleJobPort.detail(eq(JOB), eq(USER_ID))).thenReturn(new JobDetail(
			JOB_ID, JobStatus.FAILURE, FailureCause.SERVER_FAULT, false, null, null, null));

		mockMvc.perform(get("/api/v1/jobs/{jobId}", JOB_ID)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILURE"))
			.andExpect(jsonPath("$.failureCause").value("SERVER_FAULT"))
			.andDo(document("subtitle-detail-failed",
				requestPreprocessor(), responsePreprocessor()));
	}

	@Test
	@DisplayName("없는 작업 조회는 404 SUBTITLE_001 이고 본문은 code·message 뿐이다")
	void 조회_없는_작업() throws Exception {
		when(subtitleJobPort.detail(eq(JOB), eq(USER_ID)))
			.thenThrow(new BusinessException(ErrorCode.SUBTITLE_001));

		expectError(get("/api/v1/jobs/{jobId}", JOB_ID), status().isNotFound(), ErrorCode.SUBTITLE_001,
			"subtitle-detail-fail-not-found",
			"SUBTITLE_001 — 작업을 찾을 수 없다. 남의 작업도 같은 답이다(존재 비노출)");
	}

	@Test
	@DisplayName("목록은 최근 작업과 상태를 답한다 — 만료 작업도 사라지지 않는다")
	void 목록() throws Exception {
		when(subtitleJobPort.list(USER_ID)).thenReturn(new JobList(List.of(
			new JobList.Item(6L, JobStatus.REQUEST_SCRIPT, false, LocalDateTime.of(2026, 8, 19, 12, 0)),
			new JobList.Item(5L, JobStatus.COMPLETED_SUBTITLE, true, LocalDateTime.of(2026, 7, 1, 9, 30)))));

		mockMvc.perform(get("/api/v1/jobs")
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs.length()").value(2))
			.andExpect(jsonPath("$.jobs[0].jobId").value(6))
			.andExpect(jsonPath("$.jobs[0].status").value("REQUEST_SCRIPT"))
			.andExpect(jsonPath("$.jobs[1].expired").value(true))
			.andDo(document("subtitle-list",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("jobs[]").description("최근 작업 목록 — 최신순. 없으면 빈 배열이다"),
					fieldWithPath("jobs[].jobId").description("작업 번호"),
					fieldWithPath("jobs[].status").description("작업 진행 상태 — 상태 조회와 같은 값 집합"),
					fieldWithPath("jobs[].expired").description("원본 보관 기간 경과 여부 — 만료돼도 목록에 남는다"),
					fieldWithPath("jobs[].createdAt").description("작업 생성 시각(ISO-8601)"))));
	}

	@Test
	@DisplayName("작업이 없으면 200 의 빈 jobs 다 — 조회 실패와 모양이 다르다")
	void 목록_빈_목록() throws Exception {
		when(subtitleJobPort.list(USER_ID)).thenReturn(new JobList(List.of()));

		mockMvc.perform(get("/api/v1/jobs")
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobs.length()").value(0))
			.andDo(document("subtitle-list-empty",
				requestPreprocessor(), responsePreprocessor()));
	}

	@Test
	@DisplayName("목록 조회 실패는 빈 목록이 아니라 500 COMMON_002 다")
	void 목록_조회_실패() throws Exception {
		when(subtitleJobPort.list(USER_ID)).thenThrow(new IllegalStateException("db unavailable"));

		mockMvc.perform(get("/api/v1/jobs")
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("COMMON_002"))
			.andExpect(jsonPath("$.jobs").doesNotExist())
			.andDo(document("subtitle-list-fail-error",
				requestPreprocessor(), responsePreprocessor()));
	}

	private void expectError(MockHttpServletRequestBuilder request, ResultMatcher expectedStatus,
			ErrorCode errorCode, String snippet, String codeDescription) throws Exception {

		mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID)))
			.andExpect(expectedStatus)
			.andExpect(jsonPath("$.code").value(errorCode.name()))
			.andExpect(jsonPath("$.message").value(errorCode.getMessage()))
			.andExpect(jsonPath("$.scriptUrl").doesNotExist())
			.andExpect(jsonPath("$.subtitleUrl").doesNotExist())
			.andDo(document(snippet,
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description(codeDescription),
					fieldWithPath("message").description("안내 문구"))));
	}
}
