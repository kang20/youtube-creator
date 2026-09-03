package kang20.ytcreator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(BootstrapController.class)
class BootstrapControllerTest extends ControllerTest {

	private static final String BOOTSTRAP_PATH = "/api/v1/bootstrap";
	private static final String ANON_KEY = AnonymousKeyFixture.VALID;
	private static final UserId USER = new UserId(42L);
	private static final LocalDateTime REGISTERED_AT = LocalDateTime.of(2026, 8, 11, 10, 30, 0);
	private static final String ACCESS = "eyJhbGciOiJIUzI1NiJ9.doc-access";
	private static final String REFRESH = "AAAA-doc-refresh";

	@MockitoBean
	private AuthPort authPort;

	@Test
	@DisplayName("부트스트랩은 등록 결과와 토큰 쌍을 한 응답으로 준다 — userId 는 싣지 않는다")
	void 진입_성공() throws Exception {
		when(authPort.login(ANON_KEY))
			.thenReturn(new LoginResult(true, REGISTERED_AT, USER, ACCESS, REFRESH));

		MvcResult result = mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, ANON_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newUser").value(true))
			.andExpect(jsonPath("$.registeredAt").exists())
			// (v4) U7 — 부트스트랩이 곧 로그인: 토큰 쌍이 동봉된다
			.andExpect(jsonPath("$.auth.accessToken").value(ACCESS))
			.andExpect(jsonPath("$.auth.refreshToken").value(REFRESH))
			// §5-2 — userId 는 프론트 계약이 아니다
			.andExpect(jsonPath("$.userId").doesNotExist())
			.andDo(document("bootstrap-entry",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(BootstrapController.ANONYMOUS_KEY_HEADER)
					.description("익명키 — getAnonymousKey() 값. v4 부터 이 엔드포인트에서만 쓴다"
						+ "(이후 전 API 는 Authorization: Bearer)")),
				responseFields(
					fieldWithPath("newUser").description("이번 진입으로 등록됐으면 true — 온보딩 분기"),
					fieldWithPath("registeredAt").description("최초 등록 시각"),
					fieldWithPath("auth.accessToken")
						.description("JWT(30분). 이후 전 API 의 Authorization: Bearer 로 보낸다."
							+ " 만료(AUTH_004)면 /auth/refresh 로 갱신"),
					fieldWithPath("auth.refreshToken")
						.description("갱신용 불투명 값(14일·회전). 로컬에만 보관한다."
							+ " 재호출 = 재로그인 — 매번 새로 발급되고 기존 값은 폐기되지 않는다"))))
			.andReturn();

		// U6(auth) — 익명키 원문이 응답에 실리지 않는다
		assertThat(result.getResponse().getContentAsString()).doesNotContain(ANON_KEY);
	}

	@Test
	@DisplayName("익명키 헤더가 없으면 401 AUTH_001 이다 — 프론트는 SDK 1회 재호출")
	void 진입_실패_헤더_없음() throws Exception {
		mockMvc.perform(post(BOOTSTRAP_PATH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_001"))
			.andDo(document("bootstrap-entry-fail-missing-key",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("AUTH_001 — 익명키 없음. getAnonymousKey() 재호출 후 1회 재시도"),
					fieldWithPath("message").description("안내 문구"))));
	}

	@Test
	@DisplayName("공백 익명키는 헤더 없음과 동일 취급 — 401 AUTH_001")
	void 진입_실패_공백_헤더() throws Exception {
		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, AnonymousKeyFixture.BLANK))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_001"));
	}

	@Test
	@DisplayName("형식이 틀린 익명키는 401 AUTH_002 다 — 재시도 무익, 안내 후 종료")
	void 진입_실패_형식_위반() throws Exception {
		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, AnonymousKeyFixture.tooLong()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_002"))
			.andDo(document("bootstrap-entry-fail-malformed-key",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("AUTH_002 — 형식 위반. AUTH_001 과 달리 재시도가 무익하다(섞으면 무한 재시도)"),
					fieldWithPath("message").description("안내 문구"))));
	}

	@Test
	@DisplayName("상한 길이 정각의 익명키는 형식 검증을 통과한다")
	void 상한_길이는_통과한다() throws Exception {
		String atMax = AnonymousKeyFixture.atMaxLength();
		when(authPort.login(atMax))
			.thenReturn(new LoginResult(true, REGISTERED_AT, USER, ACCESS, REFRESH));

		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, atMax))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.auth.accessToken").value(ACCESS));
	}
}
