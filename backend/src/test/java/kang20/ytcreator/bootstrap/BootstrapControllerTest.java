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
import kang20.ytcreator.auth.dto.Registration;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.shared.security.AnonymousKeyFilter;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code POST /api/v1/bootstrap} HTTP 계약 + REST Docs
 * (payment-design.md §2-2 · §8 · §10 {@code BootstrapControllerTest} — auth.md §5-2 v3 확정 계약).
 *
 * <p>✅ auth-design §8 이 남긴 숙제가 여기서 닫힌다 — <b>게이트 401 두 종류</b>(AUTH_001/AUTH_002)를
 * 문서화할 실제 엔드포인트가 생겼다.
 */
@WebMvcTest(BootstrapController.class)
class BootstrapControllerTest extends ControllerTest {

	private static final String BOOTSTRAP_PATH = "/api/v1/bootstrap";
	private static final String ANON_KEY = AnonymousKeyFixture.VALID;
	private static final UserId USER = new UserId(42L);
	private static final LocalDateTime REGISTERED_AT = LocalDateTime.of(2026, 8, 11, 10, 30, 0);

	@MockitoBean
	private AuthPort authPort;

	@MockitoBean
	private PaymentReaderPort paymentReader;

	/**
	 * auth.md §5-2 v3 — 응답은 {@code {newUser, registeredAt, entitlement}} 이고 entitlement 내부는
	 * payment.md §5-3 이 정본이다. ⚠️ {@code userId} 는 싣지 않는다(§2-2 — 서버 내부 식별자).
	 */
	@Test
	@DisplayName("부트스트랩은 등록 결과와 이용권을 한 응답으로 준다 — userId 는 싣지 않는다")
	void 진입_성공() throws Exception {
		when(authPort.register(ANON_KEY)).thenReturn(new Registration(true, REGISTERED_AT, USER));
		OffsetDateTime expiresAt = LocalDateTime.of(2026, 9, 8, 0, 0).atOffset(ZoneOffset.ofHours(9));
		when(paymentReader.entitlementOf(USER)).thenReturn(new EntitlementView(true, 2, false,
			new EntitlementView.SubscriptionView("ACTIVE", expiresAt, true)));

		MvcResult result = mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(AnonymousKeyFilter.HEADER, ANON_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.newUser").value(true))
			.andExpect(jsonPath("$.registeredAt").exists())
			.andExpect(jsonPath("$.entitlement.accessible").value(true))
			.andExpect(jsonPath("$.entitlement.credits").value(2))
			.andExpect(jsonPath("$.entitlement.subscriptionStale").value(false))
			.andExpect(jsonPath("$.entitlement.subscription.status").value("ACTIVE"))
			.andExpect(jsonPath("$.entitlement.subscription.expiresAt").value("2026-09-08T00:00:00+09:00"))
			.andExpect(jsonPath("$.entitlement.subscription.autoRenew").value(true))
			// §2-2 — userId 는 프론트 계약이 아니다
			.andExpect(jsonPath("$.userId").doesNotExist())
			.andDo(document("bootstrap-entry",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(AnonymousKeyFilter.HEADER)
					.description("익명키 — getAnonymousKey() 값. 전 구간 단일 식별")),
				responseFields(
					fieldWithPath("newUser").description("이번 진입으로 등록됐으면 true — 온보딩 분기"),
					fieldWithPath("registeredAt").description("최초 등록 시각"),
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
	 * auth.md §4-2 · §6-2 — 헤더 없음 → 401 {@code AUTH_001}. 프론트는 SDK 를 1회 재호출한다.
	 * auth-design §8 의 "게이트 401 문서화는 bootstrap 구현 시" 가 여기서 실현된다.
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

	/** auth.md §4-2 · §6-1 — 형식 위반 → 401 {@code AUTH_002}. 재호출해도 소용없다 — 안내 후 종료 */
	@Test
	@DisplayName("형식이 틀린 익명키는 401 AUTH_002 다 — 재시도 무익, 안내 후 종료")
	void 진입_실패_형식_위반() throws Exception {
		mockMvc.perform(post(BOOTSTRAP_PATH)
				.header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.tooLong()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_002"))
			.andDo(document("bootstrap-entry-fail-malformed-key",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("AUTH_002 — 형식 위반. AUTH_001 과 달리 재시도가 무익하다(섞으면 무한 재시도)"),
					fieldWithPath("message").description("안내 문구"))));
	}
}
