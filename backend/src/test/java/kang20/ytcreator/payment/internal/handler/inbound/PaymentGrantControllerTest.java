package kang20.ytcreator.payment.internal.handler.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@code POST /api/v1/payments/grant} HTTP 계약 + REST Docs — payment.md §5-4 ·
 * payment-design.md §11 스니펫 표({@code payment-grant} 외 5종).
 *
 * <p><b>인증 게이트 뒤다</b> — 모든 요청이 Bearer 를 싣는다. 토큰 없이 401 이 되는 것은
 * 게이트의 계약이라 {@code SecurityGateTest} 가 본다.
 */
@WebMvcTest(PaymentGrantController.class)
class PaymentGrantControllerTest extends ControllerTest {

	private static final String GRANT_PATH = "/api/v1/payments/grant";

	private static final UserId USER_ID = new UserId(1L);

	private static final String BODY = "{ \"orderId\": \"13c9a1ff-6f0e-4a2b-9f10-5c7a1e2d3b4c\" }";

	@MockitoBean
	private PaymentPurchasePort paymentPurchasePort;

	/**
	 * 🔴 <b>신규 지급과 재요청이 같은 200 이다</b> — 중복 호출은 장애가 아니라 정상 경로다
	 * (콜백·미결 복원·네트워크 재시도가 전부 중복을 만든다). 프론트는 200 이면 콜백에 {@code true} 를 준다.
	 */
	@Test
	@DisplayName("지급은 200 으로 무엇을 지급했는지 답한다 — 재요청도 같은 응답이다")
	void 지급_성공() throws Exception {
		when(paymentPurchasePort.grant(eq(USER_ID), any(OrderId.class)))
			.thenReturn(new GrantResult(true, ProductType.CONSUMABLE));

		mockMvc.perform(post(GRANT_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(true))
			.andExpect(jsonPath("$.productType").value("CONSUMABLE"))
			.andDo(document("payment-grant",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION)
					.description("Bearer access 토큰 — 이 토큰의 사용자가 그대로 주문의 소유자가 된다(선점)")),
				requestFields(fieldWithPath("orderId")
					.description("토스가 발급한 주문 식별자. <b>sku 는 보내지 않는다</b> — 무엇을 샀는지는"
						+ " 토스가 답한 값으로만 판별한다")),
				responseFields(
					fieldWithPath("granted").description("지급됐으면 true. 재요청도 true 다(멱등)"),
					fieldWithPath("productType")
						.description("무엇을 지급했는지 — CONSUMABLE(횟수권) · SUBSCRIPTION(구독). 화면 분기용"))));
	}

	/** 주문이 아직 진행 중이다 — 잠시 후 다시 시도하면 되는 상태라 재시도를 막지 않는다. */
	@Test
	@DisplayName("결제가 확정되지 않은 주문은 409 PAY_002 다 — 잠시 후 재시도")
	void 지급_실패_진행중() throws Exception {
		grantThrows(ErrorCode.PAY_002);

		expectError(status().isConflict(), ErrorCode.PAY_002, "payment-grant-fail-in-progress",
			"PAY_002 — 주문이 아직 진행 중이다. 잠시 후 반영된다고 안내하고 복원 흐름에 맡긴다");
	}

	/** 실패·환불된 주문 — <b>재시도해도 결과가 바뀌지 않는다.</b> */
	@Test
	@DisplayName("결제 실패·환불된 주문은 409 PAY_003 이다 — 재시도 금지")
	void 지급_실패_미결제() throws Exception {
		grantThrows(ErrorCode.PAY_003);

		expectError(status().isConflict(), ErrorCode.PAY_003, "payment-grant-fail-not-purchased",
			"PAY_003 — 결제가 완료되지 않았거나 환불된 주문이다. 재시도하지 않고 문의로 안내한다");
	}

	/** 주문 없음·타 미니앱 주문 — 우리 상품이 아니면 지급하지 않는다. */
	@Test
	@DisplayName("우리 주문이 아니면 404 PAY_004 다")
	void 지급_실패_주문_없음() throws Exception {
		grantThrows(ErrorCode.PAY_004);

		expectError(status().isNotFound(), ErrorCode.PAY_004, "payment-grant-fail-not-found",
			"PAY_004 — 주문을 찾을 수 없거나 우리 미니앱의 주문이 아니다(모르는 상품 코드 포함)");
	}

	/**
	 * 🔴 선점 위반 — 이미 남의 것이 된 주문이다. 소유자는 끝까지 클라이언트의 주장이므로
	 * 이 코드는 보안 통제가 아니라 <b>먼저 온 쪽을 지키는 규칙</b>이다.
	 */
	@Test
	@DisplayName("남의 주문에 지급을 요청하면 409 PAY_005 다 — 소유자는 바뀌지 않는다")
	void 지급_실패_선점_위반() throws Exception {
		grantThrows(ErrorCode.PAY_005);

		expectError(status().isConflict(), ErrorCode.PAY_005, "payment-grant-fail-owned-by-other",
			"PAY_005 — 다른 사용자에게 귀속된 주문이다. 복원 중이면 조용히 건너뛴다");
	}

	/** 토스 응답 실패·타임아웃 — 우리 잘못이 아니고 사실을 확인하지 못한 것이다. */
	@Test
	@DisplayName("토스에 주문을 확인하지 못하면 502 PAY_006 이다 — 재시도 대상")
	void 지급_실패_상류_장애() throws Exception {
		grantThrows(ErrorCode.PAY_006);

		expectError(status().isBadGateway(), ErrorCode.PAY_006, "payment-grant-fail-upstream",
			"PAY_006 — 토스에 주문 상태를 확인하지 못했다(실패 응답·타임아웃). 재시도 대상이다");
	}

	/** 빈 orderId 는 형식 축이라 400 COMMON_001 이다 — 지급 판정(PAY_*)과 섞지 않는다. */
	@Test
	@DisplayName("orderId 가 비어 있으면 400 COMMON_001 이다")
	void 지급_요청_검증() throws Exception {
		mockMvc.perform(post(GRANT_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ \"orderId\": \"\" }"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_001"));
	}

	private void grantThrows(ErrorCode errorCode) {
		when(paymentPurchasePort.grant(eq(USER_ID), any(OrderId.class)))
			.thenThrow(new BusinessException(errorCode));
	}

	/** ⚠️ 모든 실패 응답이 {@code {code, message}} 뿐이다 — 주문 식별자를 되돌려주지 않는다(U14). */
	private void expectError(org.springframework.test.web.servlet.ResultMatcher expectedStatus,
			ErrorCode errorCode, String snippet, String codeDescription) throws Exception {

		mockMvc.perform(post(GRANT_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(expectedStatus)
			.andExpect(jsonPath("$.code").value(errorCode.name()))
			.andExpect(jsonPath("$.message").value(errorCode.getMessage()))
			.andExpect(jsonPath("$.orderId").doesNotExist())
			.andDo(document(snippet,
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description(codeDescription),
					fieldWithPath("message").description("안내 문구"))));
	}
}
