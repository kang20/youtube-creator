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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/bootstrap} HTTP 계약 + REST Docs
 * (auth.md §5-2 <b>v4</b> — 토큰 동봉 · payment-design.md §2-2 · auth-design.md §14-5 개정 행).
 *
 * <p><b>(v4) 부트스트랩이 곧 로그인이다</b>(U7) — 응답에 {@code auth{accessToken, refreshToken}} 이
 * 실린다. 익명키를 받는 곳은 이제 이 엔드포인트뿐이고(§5-1), U5 형식 검증도 게이트가 아니라
 * 이 컨트롤러가 직접 한다(auth-design.md §14-2) — 401 두 종류(AUTH_001/AUTH_002)의 문서화가
 * 여기 있는 이유다.
 */
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

	@MockitoBean
	private PaymentReaderPort paymentReader;

	/**
	 * U7 · auth.md §5-2 v4 — 응답은 {@code {newUser, registeredAt, auth{...}, entitlement{...}}} 다.
	 * entitlement 내부는 payment.md §5-3 이 정본이다.
	 * ⚠️ {@code userId} 는 싣지 않는다(§5-2 — 서버 내부 식별자).
	 */
	@Test
	@DisplayName("부트스트랩은 등록 결과·토큰 쌍·이용권을 한 응답으로 준다 — userId 는 싣지 않는다")
	void 진입_성공() throws Exception {
		when(authPort.login(ANON_KEY))
			.thenReturn(new LoginResult(true, REGISTERED_AT, USER, ACCESS, REFRESH));
		OffsetDateTime expiresAt = LocalDateTime.of(2026, 9, 8, 0, 0).atOffset(ZoneOffset.ofHours(9));
		when(paymentReader.entitlementOf(USER)).thenReturn(new EntitlementView(true, 2, false,
			new EntitlementView.SubscriptionView("ACTIVE", expiresAt, true)));

		MvcResult result = mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, ANON_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newUser").value(true))
			.andExpect(jsonPath("$.registeredAt").exists())
			// (v4) U7 — 부트스트랩이 곧 로그인: 토큰 쌍이 동봉된다
			.andExpect(jsonPath("$.auth.accessToken").value(ACCESS))
			.andExpect(jsonPath("$.auth.refreshToken").value(REFRESH))
			.andExpect(jsonPath("$.entitlement.accessible").value(true))
			.andExpect(jsonPath("$.entitlement.credits").value(2))
			.andExpect(jsonPath("$.entitlement.subscriptionStale").value(false))
			.andExpect(jsonPath("$.entitlement.subscription.status").value("ACTIVE"))
			.andExpect(jsonPath("$.entitlement.subscription.expiresAt").value("2026-09-08T00:00:00+09:00"))
			.andExpect(jsonPath("$.entitlement.subscription.autoRenew").value(true))
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
							+ " 재호출 = 재로그인 — 매번 새로 발급되고 기존 값은 폐기되지 않는다"),
					fieldWithPath("entitlement.accessible")
						.description("이용 가능 여부 — S1 배지 분기는 이 값 하나로만(payment.md §5-3)"),
					fieldWithPath("entitlement.credits").description("남은 횟수권 — 'n회 남음' 배지"),
					fieldWithPath("entitlement.subscriptionStale")
						.description("true 면 결제 유도가 아니라 구독 재확인 흐름으로 간다"),
					fieldWithPath("entitlement.subscription.status")
						.description("구독 상태 6종 + NONE. 안내 문구용"),
					fieldWithPath("entitlement.subscription.expiresAt").optional()
						.description("만료 시각(+09:00). 이력 없으면 null"),
					fieldWithPath("entitlement.subscription.autoRenew").description("자동 갱신 예정"))))
			.andReturn();

		// U6(auth) — 익명키 원문이 응답에 실리지 않는다
		assertThat(result.getResponse().getContentAsString()).doesNotContain(ANON_KEY);
	}

	/**
	 * auth.md §4-2 · §6-1 — 헤더 없음 → 401 {@code AUTH_001}(등록할 대상이 없다).
	 * 부트스트랩은 공개 경로지만 <b>익명키 헤더 자체는 필수</b>다. 프론트는 SDK 를 1회 재호출한다.
	 * (round-1-dev.md 판단 2 — hasText 실패 = AUTH_001, 형식 위반 = AUTH_002 로 분리)
	 */
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

	/** auth.md §4-4 — 공백 헤더는 "헤더 없음"과 동일 취급이라 AUTH_002 가 아니라 AUTH_001 이다. */
	@Test
	@DisplayName("공백 익명키는 헤더 없음과 동일 취급 — 401 AUTH_001")
	void 진입_실패_공백_헤더() throws Exception {
		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, AnonymousKeyFixture.BLANK))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_001"));
	}

	/** U5 · auth.md §4-2 · §6-1 — 형식 위반 → 401 {@code AUTH_002}. 재호출해도 소용없다 — 안내 후 종료 */
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

	/**
	 * U5 경계 — 상한 길이 <b>정각</b>은 형식 검증을 통과해 login 까지 간다(auth-design.md §12-2).
	 * 상한을 좁히는 변경이 오면 AUTH_002 로 떨어져 여기서 먼저 빨개진다.
	 */
	@Test
	@DisplayName("상한 길이 정각의 익명키는 형식 검증을 통과한다")
	void 상한_길이는_통과한다() throws Exception {
		String atMax = AnonymousKeyFixture.atMaxLength();
		when(authPort.login(atMax))
			.thenReturn(new LoginResult(true, REGISTERED_AT, USER, ACCESS, REFRESH));
		when(paymentReader.entitlementOf(USER)).thenReturn(new EntitlementView(false, 0, false,
			EntitlementView.SubscriptionView.none()));

		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(BootstrapController.ANONYMOUS_KEY_HEADER, atMax))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.auth.accessToken").value(ACCESS));
	}
}
