package kang20.ytcreator.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.payment.dto.WebhookEvent;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 토스 웹훅 HTTP 계약 + REST Docs (payment-design.md §8 · §10 {@code PaymentWebhookControllerTest}).
 *
 * <p>덮는 것: 204(성공·<b>반영 실패 포함</b>) · 401(U11) · <b>익명키 게이트 밖</b>(permitAll —
 * 토스는 X-Anonymous-Key 를 보내지 않는다. §10 config 행: 동작은 반드시 검증한다).
 */
@WebMvcTest(PaymentWebhookController.class)
class PaymentWebhookControllerTest extends ControllerTest {

	private static final String WEBHOOK_PATH = "/api/v1/webhooks/toss/payment";

	private static final String STATUS_CHANGED_BODY = """
		{ "eventType": "subscription.status_changed",
		  "eventVersion": "1.0",
		  "occurredAt": "2026-08-11T03:00:00",
		  "orderId": "order-1",
		  "sku": "sku-subscription",
		  "changeReason": "RENEWED",
		  "subscription": {
		    "previous": { "status": "ACTIVE", "accessGranted": true,
		                  "expiresAt": "2026-08-11T00:00:00", "autoRenew": true },
		    "current":  { "status": "ACTIVE", "accessGranted": true,
		                  "expiresAt": "2026-09-10T00:00:00", "autoRenew": true } } }
		""";

	@MockitoBean
	private PaymentService paymentService;

	/**
	 * U9 · §5-5 — 구독 상태 변경 수신. <b>익명키 없이 204</b> — 웹훅 경로는 게이트 밖이고
	 * (permitAll — payment.md §10-8ⓐ: 빠지면 토스 웹훅이 401 로 튕겨 U9 가 통째로 죽는다)
	 * 모듈이 Basic Auth 로 다시 막는다(§2-1 쟁점 3).
	 */
	@Test
	@DisplayName("구독 상태 변경 웹훅은 익명키 없이 수신되고 204 본문 없음으로 답한다")
	void 상태_변경_수신() throws Exception {
		doNothing().when(paymentService).handleWebhook(any(), any(WebhookEvent.class));

		mockMvc.perform(post(WEBHOOK_PATH)
				.header(HttpHeaders.AUTHORIZATION, PaymentFixture.WEBHOOK_AUTH_HEADER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""))
			.andDo(document("payment-webhook-status-changed",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION)
					.description("Basic {값} — 콘솔 등록 자격. 불일치면 처리하지 않는다(U11)")),
				requestFields(
					fieldWithPath("eventType").description("subscription.status_changed 고정"),
					fieldWithPath("eventVersion").description("1.0 고정"),
					fieldWithPath("occurredAt").description("발생 시각 — timezone 없는 ISO-8601, KST 로 해석한다(✅-7). 반영분보다 과거면 무시"),
					fieldWithPath("orderId").description("최초 구독 주문 ID — 사용자와 연결하는 유일한 고리. 모르는 값이면 무시(구독을 새로 만들지 않는다)"),
					fieldWithPath("sku").description("상품 SKU"),
					fieldWithPath("changeReason").description("변경 사유 12종 — 분기하지 않고 로그만 남긴다. 판정은 current 세 값"),
					fieldWithPath("subscription.previous").optional().description("변경 전 스냅샷 — CREATED 등에서 생략. 우리 상태와 불일치하면 유실 감지"),
					fieldWithPath("subscription.previous.status").optional().description("변경 전 상태"),
					fieldWithPath("subscription.previous.accessGranted").optional().description("접근 부여 여부 — 산식이 문서에 없어 쓰지 않는다"),
					fieldWithPath("subscription.previous.expiresAt").optional().description("변경 전 만료"),
					fieldWithPath("subscription.previous.autoRenew").optional().description("변경 전 자동 갱신"),
					fieldWithPath("subscription.current").description("변경 후 스냅샷 — 반영의 유일한 근거"),
					fieldWithPath("subscription.current.status").description("구독 상태 6종"),
					fieldWithPath("subscription.current.accessGranted").optional().description("쓰지 않는다"),
					fieldWithPath("subscription.current.expiresAt").optional().description("변경 후 만료(null 가능 — 기존 값 유지)"),
					fieldWithPath("subscription.current.autoRenew").optional().description("변경 후 자동 갱신"))));
	}

	/** U10 · §5-5 — 등록 검증 이벤트({eventType, occurredAt} 뿐 — challenge 없음). 빠뜨리면 U9 가 죽는다 */
	@Test
	@DisplayName("등록 검증 이벤트는 204 로 답해 콜백 URL 을 활성화한다")
	void 등록_검증_수신() throws Exception {
		doNothing().when(paymentService).handleWebhook(any(), any(WebhookEvent.class));

		mockMvc.perform(post(WEBHOOK_PATH)
				.header(HttpHeaders.AUTHORIZATION, PaymentFixture.WEBHOOK_AUTH_HEADER)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "eventType": "callback.registration_verification", "occurredAt": "2026-08-11T03:00:00" }
					"""))
			.andExpect(status().isNoContent())
			.andDo(document("payment-webhook-verification",
				requestPreprocessor(), responsePreprocessor(),
				requestFields(
					fieldWithPath("eventType").description("callback.registration_verification — 등록 시 1회"),
					fieldWithPath("occurredAt").description("발생 시각"))));
	}

	/** U11 · R6 — Basic 불일치는 401. 없으면 누구나 위조 웹훅으로 기간권을 흔들 수 있다 */
	@Test
	@DisplayName("Basic Auth 불일치 웹훅은 401 로 거부된다")
	void 위조_웹훅_거부() throws Exception {
		doThrow(new BusinessException(ErrorCode.AUTH_002))
			.when(paymentService).handleWebhook(any(), any(WebhookEvent.class));

		mockMvc.perform(post(WEBHOOK_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Basic d3Jvbmc6d3Jvbmc=")
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_002"))
			.andDo(document("payment-webhook-fail-unauthorized",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("AUTH_002 — 자격 불일치. 본문은 처리되지 않았다"),
					fieldWithPath("message").description("안내 문구"))));
	}

	/**
	 * §5-4⑤ — <b>반영 실패해도 204.</b> 서비스가 반영 실패를 안으로 삼키는 것은
	 * {@code PaymentWebhookTest}(반영_실패도_수신은_성공)가 검증하고, 여기는 "서비스가 정상 반환하면
	 * 컨트롤러는 무조건 204"라는 계약을 고정한다 — 재전송 정책이 없어 5xx 로 답해도 다시 오지 않는다.
	 */
	@Test
	@DisplayName("Authorization 헤더가 아예 없는 요청도 컨트롤러는 받는다 — 판정은 모듈 몫이다")
	void 헤더_없는_요청도_모듈이_판정한다() throws Exception {
		doThrow(new BusinessException(ErrorCode.AUTH_002))
			.when(paymentService).handleWebhook(isNull(), any(WebhookEvent.class));

		mockMvc.perform(post(WEBHOOK_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_002"));
	}
}
