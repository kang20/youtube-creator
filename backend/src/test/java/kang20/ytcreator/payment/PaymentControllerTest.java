package kang20.ytcreator.payment;

import kang20.ytcreator.payment.internal.entity.Subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import kang20.ytcreator.auth.AuthService;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.dto.Registration;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductCatalog;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.shared.security.AnonymousKeyFilter;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * payment HTTP 계약 + REST Docs (payment-design.md §8 스니펫 표 · §10 {@code PaymentControllerTest}).
 *
 * <p><b>계약 단언은 payment.md §5 원문이 기준이다</b> — 프론트가 읽는 유일한 창구는 이 스니펫으로
 * 만든 HTML 이라(§8-2), 필드명이 원문과 다르면 그대로 프론트 장애다.
 *
 * <p>U14 — 성공·실패 어느 응답 본문에도 {@code orderId} 가 실리지 않는다.
 * ⚠️ 문서 예시의 orderId 는 명백한 더미("order-...")만 쓴다(§8).
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest extends ControllerTest {

	private static final String ANON_KEY = AnonymousKeyFixture.VALID;
	private static final UserId USER = new UserId(77L);

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private PaymentService paymentService;

	@BeforeEach
	void resolveUser() {
		when(authService.register(ANON_KEY))
			.thenReturn(new Registration(false, LocalDateTime.of(2026, 8, 1, 10, 0), USER));
	}

	private static EntitlementView creditsOnly(int credits) {
		return new EntitlementView(credits > 0, credits, false, EntitlementView.SubscriptionView.none());
	}

	private static EntitlementView activeSubscription() {
		OffsetDateTime expiresAt =
			LocalDateTime.of(2026, 9, 8, 0, 0).atOffset(ZoneOffset.ofHours(9));
		return new EntitlementView(true, 0, false,
			new EntitlementView.SubscriptionView("ACTIVE", expiresAt, true));
	}

	private static EntitlementView staleSubscription() {
		OffsetDateTime expiresAt =
			LocalDateTime.of(2026, 8, 8, 0, 0).atOffset(ZoneOffset.ofHours(9));
		return new EntitlementView(false, 0, true,
			new EntitlementView.SubscriptionView("ACTIVE", expiresAt, true));
	}

	// ── U1 — GET /payments/products (§5-2) ──────────────────────────────

	/**
	 * U1 · payment.md §5-2 — 응답은 상품별 <b>중첩 객체</b>다:
	 * {@code { oneTime: {sku, productType}, subscription: {sku, productType, renewalCycle, offerId} }}.
	 * {@code productType} 이 어느 주문 함수를 쓸지 결정하고(K2), 미노출 상품은 키가 null 이다(K3).
	 */
	@Test
	@DisplayName("상품 목록은 §5-2 의 중첩 스키마로 내려간다 — 가격은 없다(SDK displayAmount 가 정본)")
	void 상품_목록() throws Exception {
		when(paymentService.products()).thenReturn(new ProductCatalog(
			new ProductCatalog.OneTime(PaymentFixture.ONE_TIME_SKU, "CONSUMABLE"),
			new ProductCatalog.Subscription(PaymentFixture.SUBSCRIPTION_SKU, "SUBSCRIPTION", "MONTHLY", null)));

		mockMvc.perform(get("/api/v1/payments/products"))
			.andExpect(status().isOk())
			// payment.md §5-2 원문 — 프론트는 이 모양으로 displayAmount 를 찾고 주문 함수를 고른다
			.andExpect(jsonPath("$.oneTime.sku").value(PaymentFixture.ONE_TIME_SKU))
			.andExpect(jsonPath("$.oneTime.productType").value("CONSUMABLE"))
			.andExpect(jsonPath("$.subscription.sku").value(PaymentFixture.SUBSCRIPTION_SKU))
			.andExpect(jsonPath("$.subscription.productType").value("SUBSCRIPTION"))
			.andExpect(jsonPath("$.subscription.renewalCycle").value("MONTHLY"))
			.andExpect(jsonPath("$.subscription.offerId").doesNotExist())
			.andDo(document("payment-products",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("oneTime").description("단건 상품. null 이면 미노출 — 결제 버튼을 숨긴다"),
					fieldWithPath("oneTime.sku")
						.description("상품 고유 ID — 이 값으로 getProductItemList() 의 displayAmount 를 찾는다. 가격 하드코딩 금지"),
					fieldWithPath("oneTime.productType")
						.description("CONSUMABLE → createOneTimePurchaseOrder 로 주문한다"),
					fieldWithPath("subscription").description("구독 상품. null 이면 미노출"),
					fieldWithPath("subscription.sku").description("구독 상품 고유 ID"),
					fieldWithPath("subscription.productType")
						.description("SUBSCRIPTION → createSubscriptionPurchaseOrder 로 주문한다"),
					fieldWithPath("subscription.renewalCycle").description("MONTHLY = 30일 고정(달력 월 아님)"),
					fieldWithPath("subscription.offerId").optional()
						.description("프로모션 오퍼. null 이면 기본 가격"))));
	}

	/** SecurityConfig 검증(§10 config 행) — 상품 조회는 공개 경로다. 익명키 없이 200 */
	@Test
	@DisplayName("상품 조회는 익명키 없이 호출된다 — 결제하려는 API 가 결제를 요구할 수 없다")
	void 상품_조회는_공개다() throws Exception {
		when(paymentService.products()).thenReturn(new ProductCatalog(
			new ProductCatalog.OneTime(PaymentFixture.ONE_TIME_SKU, "CONSUMABLE"), null));

		mockMvc.perform(get("/api/v1/payments/products"))
			.andExpect(status().isOk());
	}

	// ── U2·U3·U12 — POST /payments/grant (§5-4) ─────────────────────────

	/**
	 * U2 · payment.md §5-4 — 성공 응답 원문은
	 * {@code { granted, productType, entitlement: {...} }} 다. 프론트는 200 이면 콜백에 true 를
	 * 반환한다(K4). 신규 지급과 재요청(U3·U12)이 같은 응답이다.
	 */
	@Test
	@DisplayName("지급 성공 응답은 granted·productType·entitlement 다 — §5-4 원문 필드명")
	void 지급_성공() throws Exception {
		when(paymentService.grant(eq(USER), eq("order-doc-1")))
			.thenReturn(new GrantResult(true, ProductType.CONSUMABLE, creditsOnly(3)));

		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-1\" }"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(true))
			// 🔴 payment.md §5-4 원문 키는 "productType" 이다 — 프론트 계약
			.andExpect(jsonPath("$.productType").value("CONSUMABLE"))
			.andExpect(jsonPath("$.entitlement.accessible").value(true))
			.andExpect(jsonPath("$.entitlement.credits").value(3))
			.andExpect(jsonPath("$.entitlement.subscriptionStale").value(false))
			.andExpect(jsonPath("$.entitlement.subscription.status").value("NONE"))
			.andDo(document("payment-grant",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(AnonymousKeyFilter.HEADER).description("익명키(전 구간 단일 식별)")),
				requestFields(fieldWithPath("orderId")
					.description("processProductGrant 콜백 또는 getPendingOrders() 의 주문 ID. sku 는 보내지 않는다 — 서버가 토스 응답으로 판별한다")),
				responseFields(
					fieldWithPath("granted").description("지급된 상태면 true. 재요청(복원 재전송)도 true — 200 이면 콜백에 true 를 반환한다"),
					fieldWithPath("productType").description("토스 sku 로 판별된 상품 유형(CONSUMABLE | SUBSCRIPTION)"),
					fieldWithPath("entitlement.accessible").description("지급 반영 후 이용 가능 여부 — 분기는 이 값 하나로만"),
					fieldWithPath("entitlement.credits").description("남은 횟수권 — 재요청이어도 현재 잔량이다(+1 재반영이면 멱등 위반)"),
					fieldWithPath("entitlement.subscriptionStale").description("구독 상태 미확인 신호 — true 면 recheck 흐름"),
					fieldWithPath("entitlement.subscription.status").description("구독 상태 6종 또는 NONE. 분기 근거로 쓰지 않는다"),
					fieldWithPath("entitlement.subscription.expiresAt").optional().description("만료 시각(+09:00). 없으면 null"),
					fieldWithPath("entitlement.subscription.autoRenew").description("자동 갱신 예정 여부"))));
	}

	/**
	 * payment.md §5-4 표 · §7 — 지급 불가 4종의 HTTP 상태·코드. 프론트 행동이 코드마다 다르다(K6):
	 * PAY_002 대기(복원 위임) · PAY_003/004/005 재시도 금지 · PAY_006 재시도(복원 위임).
	 */
	@Test
	@DisplayName("결제 미확정 주문은 409 PAY_002 다 — 프론트는 false 반환 후 복원에 위임한다")
	void 지급_실패_진행중() throws Exception {
		when(paymentService.grant(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.PAY_002));

		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-2\" }"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAY_002"))
			.andDo(document("payment-grant-fail-in-progress",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_002 — 결제가 아직 확정되지 않았다. 미결 주문을 닫지 말고 다음 기동의 복원에 맡긴다"),
					fieldWithPath("message").description("사용자 안내 문구"))));
	}

	@Test
	@DisplayName("실패·환불 주문은 409 PAY_003 다 — 재시도 금지")
	void 지급_실패_미완료() throws Exception {
		when(paymentService.grant(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.PAY_003));

		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-3\" }"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAY_003"))
			.andDo(document("payment-grant-fail-not-purchased",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_003 — 결제가 완료되지 않은 주문. 안내 후 재시도 금지"),
					fieldWithPath("message").description("사용자 안내 문구"))));
	}

	@Test
	@DisplayName("없는 주문은 404 PAY_004 다 — 문의 안내·재시도 금지")
	void 지급_실패_주문_없음() throws Exception {
		when(paymentService.grant(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.PAY_004));

		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-4\" }"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAY_004"))
			.andDo(document("payment-grant-fail-not-found",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_004 — 주문 없음·타 미니앱 주문. 문의 안내, 재시도 금지"),
					fieldWithPath("message").description("사용자 안내 문구"))));
	}

	/** U4·U14 — 남의 주문(409 PAY_005). 복원 흐름에서는 사용자에게 노출하지 않고 조용히 건너뛴다 */
	@Test
	@DisplayName("남의 주문은 409 PAY_005 다 — 복원 흐름에서는 조용히 건너뛴다")
	void 지급_실패_선점() throws Exception {
		when(paymentService.grant(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.PAY_005));

		MvcResult result = mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-5\" }"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAY_005"))
			.andDo(document("payment-grant-fail-owned-by-other",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_005 — 다른 사용자에게 귀속된 주문(선점 규칙). 복원 중이면 사용자 노출 없이 건너뛴다"),
					fieldWithPath("message").description("사용자 안내 문구"))))
			.andReturn();

		// U14 — 에러 응답에 orderId 를 되돌려주지 않는다(§5-4)
		assertThat(result.getResponse().getContentAsString()).doesNotContain("order-doc-5");
	}

	/** R9 — 토스 장애(502 PAY_006). GlobalExceptionHandler 신설 없이 getStatus() 로 처리된다(§9) */
	@Test
	@DisplayName("토스 확인 실패는 502 PAY_006 다 — 잠시 후 재시도, 복원에 위임")
	void 지급_실패_토스_장애() throws Exception {
		when(paymentService.grant(eq(USER), any())).thenThrow(new BusinessException(ErrorCode.PAY_006));

		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"order-doc-6\" }"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("PAY_006"))
			.andDo(document("payment-grant-fail-upstream",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_006 — 결제 정보 확인 실패(토스 장애·타임아웃). false 반환 후 복원에 위임"),
					fieldWithPath("message").description("사용자 안내 문구"))));
	}

	// ── U5 — GET /payments/entitlement (§5-3) ───────────────────────────

	/** U5 · §5-3 — 부트스트랩과 같은 모양의 단독 조회. 결제 직후·403 복귀 후 재확인용 */
	@Test
	@DisplayName("이용권 조회는 §5-3 스키마 그대로다")
	void 이용권_조회() throws Exception {
		when(paymentService.entitlementOf(USER)).thenReturn(activeSubscription());

		mockMvc.perform(get("/api/v1/payments/entitlement")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessible").value(true))
			.andExpect(jsonPath("$.credits").value(0))
			.andExpect(jsonPath("$.subscriptionStale").value(false))
			.andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
			// ✅-7 · §12-4 — expiresAt 은 타임존(+09:00)을 붙여 내려준다
			.andExpect(jsonPath("$.subscription.expiresAt").value("2026-09-08T00:00:00+09:00"))
			.andExpect(jsonPath("$.subscription.autoRenew").value(true))
			.andDo(document("payment-entitlement",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(AnonymousKeyFilter.HEADER).description("익명키")),
				responseFields(
					fieldWithPath("accessible").description("이용 가능 여부 — 프론트 분기는 이 값 하나로만(§4-3 판정 결과)"),
					fieldWithPath("credits").description("남은 횟수권 — 'n회 남음' 표시"),
					fieldWithPath("subscriptionStale").description("true 면 결제 유도가 아니라 recheck 흐름으로 간다"),
					fieldWithPath("subscription.status").description("토스 원문 6종 + NONE. 안내 문구용 — 분기 근거로 쓰지 않는다"),
					fieldWithPath("subscription.expiresAt").optional().description("만료 시각(+09:00 오프셋 포함). 없으면 null"),
					fieldWithPath("subscription.autoRenew").description("자동 갱신 예정. false 면 '해지 예약됨' 표시"))));
	}

	/** §4-7-1④⑥ · K11 — STALE 신호. 프론트는 결제 유도가 아니라 §6-7 재확인 흐름으로 간다 */
	@Test
	@DisplayName("STALE 이면 subscriptionStale=true 가 실린다 — 결제 유도 금지 신호")
	void 이용권_조회_STALE() throws Exception {
		when(paymentService.entitlementOf(USER)).thenReturn(staleSubscription());

		mockMvc.perform(get("/api/v1/payments/entitlement")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessible").value(false))
			.andExpect(jsonPath("$.subscriptionStale").value(true))
			.andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
			.andDo(document("payment-entitlement-stale",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("accessible").description("STALE 동안 게이트는 닫힌다(§4-7-1⑥)"),
					fieldWithPath("credits").description("남은 횟수권"),
					fieldWithPath("subscriptionStale")
						.description("구독 상태를 서버가 확인하지 못했다 — getCompletedOrRefundedOrders → getSubscriptionInfo → recheck 순서로 해소한다(결제 유도 금지)"),
					fieldWithPath("subscription.status").description("마지막으로 확인된 상태"),
					fieldWithPath("subscription.expiresAt").optional().description("지난 만료 시각(+09:00)"),
					fieldWithPath("subscription.autoRenew").description("자동 갱신 예정"))));
	}

	// ── §4-7-1⑥ — POST /payments/subscription/recheck (§5-7) ────────────

	/** §5-7 — 요청은 클라 getSubscriptionInfo 결과. orderId 는 요청에 담지 않는다(U14) */
	@Test
	@DisplayName("recheck 는 entitlement 와 같은 스키마로 답한다")
	void 구독_재확인() throws Exception {
		when(paymentService.recheck(eq(USER), any(SubscriptionSnapshot.class)))
			.thenReturn(activeSubscription());

		mockMvc.perform(post("/api/v1/payments/subscription/recheck")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ACTIVE\", \"expiresAt\": \"2026-09-08T00:00:00+09:00\", \"autoRenew\": true }"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessible").value(true))
			.andExpect(jsonPath("$.subscriptionStale").value(false))
			.andDo(document("payment-subscription-recheck",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(AnonymousKeyFilter.HEADER).description("익명키")),
				requestFields(
					fieldWithPath("status").description("getSubscriptionInfo 의 status 원문. orderId 는 보내지 않는다 — SDK 에서 직접 얻는 값이라 서버가 알 필요 없다"),
					fieldWithPath("expiresAt").optional().description("만료 시각 — 오프셋 포함 형식. SDK 가 null 을 줄 수 있다"),
					fieldWithPath("autoRenew").description("isAutoRenew 값")),
				responseFields(
					fieldWithPath("accessible").description("반영 후 이용 가능 여부 — true 면 게이트 재개방"),
					fieldWithPath("credits").description("남은 횟수권"),
					fieldWithPath("subscriptionStale").description("해소되면 false"),
					fieldWithPath("subscription.status").description("반영된 상태"),
					fieldWithPath("subscription.expiresAt").optional().description("반영된 만료 시각(+09:00)"),
					fieldWithPath("subscription.autoRenew").description("자동 갱신 예정"))));
	}

	/** §5-5 — 구독 이력이 없는데 재확인할 게 없다(404 PAY_004) */
	@Test
	@DisplayName("구독 이력이 없으면 recheck 는 404 PAY_004 다")
	void 구독_재확인_이력_없음() throws Exception {
		when(paymentService.recheck(eq(USER), any(SubscriptionSnapshot.class)))
			.thenThrow(new BusinessException(ErrorCode.PAY_004));

		mockMvc.perform(post("/api/v1/payments/subscription/recheck")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"status\": \"ACTIVE\", \"expiresAt\": null, \"autoRenew\": true }"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAY_004"))
			.andDo(document("payment-subscription-recheck-fail-not-found",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("PAY_004 — 구독 이력 없음. recheck 대상이 아니다"),
					fieldWithPath("message").description("사용자 안내 문구"))));
	}

	/** COMMON_001 — @NotBlank 검증. 깨진 요청은 서비스까지 가지 않는다 */
	@Test
	@DisplayName("orderId 가 비어 있으면 400 COMMON_001 이다")
	void 지급_요청_검증() throws Exception {
		mockMvc.perform(post("/api/v1/payments/grant")
				.header(AnonymousKeyFilter.HEADER, ANON_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"\" }"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_001"));
	}
}
