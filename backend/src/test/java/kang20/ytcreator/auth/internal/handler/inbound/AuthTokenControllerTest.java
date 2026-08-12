package kang20.ytcreator.auth.internal.handler.inbound;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.TokenPair;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code POST /api/v1/auth/refresh} HTTP 계약 + REST Docs — auth.md §5-5 · auth-design.md §14-5
 * ({@code auth-refresh} · {@code auth-refresh-fail-invalid}).
 *
 * <p><b>인증 게이트 밖(공개 경로)</b>이다 — access 가 만료된 상태에서 부르는 API 라
 * {@code Authorization} 없이 동작해야 한다. 이 테스트의 모든 요청이 Bearer 를 싣지 않는 것
 * 자체가 그 계약의 검증이다(SecurityConfig 를 import 하는 베이스 위에서 돈다).
 */
@WebMvcTest(AuthTokenController.class)
class AuthTokenControllerTest extends ControllerTest {

	private static final String REFRESH_PATH = "/api/v1/auth/refresh";

	@MockitoBean
	private AuthPort authPort;

	/**
	 * U9 · auth.md §5-5 — 응답 200 은 회전된 새 쌍 {@code {accessToken, refreshToken}} 이다.
	 * 요청에 쓴 refresh 는 그 순간 폐기됐다(회전의 실체는 {@code RefreshRotationTest} 가 검증하고,
	 * 여기는 HTTP 계약이다).
	 */
	@Test
	@DisplayName("refresh 는 게이트 밖에서 새 토큰 쌍을 준다 — 요청에 쓴 refresh 는 그 순간 폐기된다")
	void 갱신_성공() throws Exception {
		when(authPort.refresh("AAAA-current-refresh"))
			.thenReturn(new TokenPair("eyJhbGciOiJIUzI1NiJ9.doc-access", "BBBB-next-refresh"));

		mockMvc.perform(post(REFRESH_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"refreshToken\": \"AAAA-current-refresh\" }"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("eyJhbGciOiJIUzI1NiJ9.doc-access"))
			.andExpect(jsonPath("$.refreshToken").value("BBBB-next-refresh"))
			.andDo(document("auth-refresh",
				requestPreprocessor(), responsePreprocessor(),
				requestFields(fieldWithPath("refreshToken")
					.description("보관 중인 refresh 원문 — 본문이 곧 자격 증명이다(Authorization 불필요)."
						+ " 이 요청이 성공하는 순간 이 값은 폐기된다(회전)")),
				responseFields(
					fieldWithPath("accessToken")
						.description("새 JWT(30분). 이후 전 API 의 Authorization: Bearer 로 쓴다"),
					fieldWithPath("refreshToken")
						.description("새 refresh(14일). 기존 값을 이걸로 교체해 로컬에만 보관한다"))));
	}

	/**
	 * U9 · auth.md §5-5 실패 표 — 만료·미존재·재사용 전부 <b>401 {@code AUTH_005}</b> 하나다.
	 * 프론트 행동이 전부 "부트스트랩 재로그인"으로 같아서 코드를 가르지 않는다(§6-2 인터셉터 분기).
	 */
	@Test
	@DisplayName("만료·미존재·재사용된 refresh 는 401 AUTH_005 다 — 부트스트랩 재로그인")
	void 갱신_실패_무효_토큰() throws Exception {
		when(authPort.refresh(anyString())).thenThrow(new BusinessException(ErrorCode.AUTH_005));

		mockMvc.perform(post(REFRESH_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"refreshToken\": \"already-rotated-or-expired\" }"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_005"))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_005.getMessage()))
			.andDo(document("auth-refresh-fail-invalid",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code")
						.description("AUTH_005 — 만료·미존재·이미 회전된 토큰의 재사용. 부트스트랩(§6-1)으로"
							+ " 재로그인한다(익명키는 SDK 가 늘 주므로 마찰 없음). 재사용은 서버가 탈취"
							+ " 신호로 보고 이 사용자의 refresh 전부를 폐기한 상태다"),
					fieldWithPath("message").description("안내 문구"))));
	}

	/**
	 * round-1-dev.md 판단 6 — <b>빈 값과 무효 값은 다른 축이다.</b> 빈 refreshToken 은 검증
	 * ({@code @NotBlank})이 걸러 400 {@code COMMON_001} 이고, AUTH_005(401)와 섞이지 않는다.
	 */
	@Test
	@DisplayName("refreshToken 이 비어 있으면 400 COMMON_001 이다 — AUTH_005 와 축이 다르다")
	void 갱신_요청_검증() throws Exception {
		mockMvc.perform(post(REFRESH_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"refreshToken\": \"\" }"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_001"));
	}
}
