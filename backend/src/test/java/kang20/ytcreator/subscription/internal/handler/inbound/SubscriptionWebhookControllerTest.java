package kang20.ytcreator.subscription.internal.handler.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.requestFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(SubscriptionWebhookController.class)
class SubscriptionWebhookControllerTest extends ControllerTest {

	private static final String WEBHOOK_PATH = "/api/v1/webhooks/toss/subscription";

	private static final String SECRET = "eXRjcmVhdG9yOnRlc3Qtd2ViaG9vay1zZWNyZXQ=";

	private static final String HEADER = "Basic " + SECRET;

	private static final String STATUS_CHANGED_BODY = """
		{
		  "eventType": "subscription.status_changed",
		  "eventVersion": "1.0",
		  "occurredAt": "2026-05-06T00:00:00",
		  "orderId": "13c9a1ff-6f0e-4a2b-9f10-5c7a1e2d3b4c",
		  "sku": "premium.monthly",
		  "changeReason": "RENEWED",
		  "subscription": {
		    "previous": { "status": "ACTIVE", "accessGranted": true, "expiresAt": "2026-05-06T00:00:00", "autoRenew": true },
		    "current":  { "status": "ACTIVE", "accessGranted": true, "expiresAt": "2026-06-06T00:00:00", "autoRenew": true }
		  }
		}
		""";

	private static final String REGISTRATION_BODY = """
		{
		  "eventType": "callback.registration_verification",
		  "eventVersion": "1.0"
		}
		""";

	@MockitoBean
	private SubscriptionStatusPort subscriptionStatusPort;

	@BeforeEach
	void 시크릿이_맞을_때만_수신한다() {
		doAnswer(invocation -> {
			if (!HEADER.equals(invocation.getArgument(0))) {
				throw new BusinessException(ErrorCode.AUTH_002);
			}
			return null;
		}).when(subscriptionStatusPort).handleWebhook(any(), any(WebhookEvent.class));
	}

	@Test
	@DisplayName("S12 — 시크릿이 맞으면 204 로 수신한다 — 본문 없음")
	void 웹훅_수신() throws Exception {
		mockMvc.perform(post(WEBHOOK_PATH)
				.header(SubscriptionWebhookController.SECRET_HEADER, HEADER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isNoContent())
			.andDo(document("subscription-webhook",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(SubscriptionWebhookController.SECRET_HEADER)
					.description("<code>Basic {콘솔에 등록한 Basic Auth 헤더 값}</code>."
						+ " 플랫폼이 주는 유일한 진위 검증 수단이다 — 서명·HMAC 은 없다."
						+ " 스킴 불일치·값 불일치·누락은 모두 401")),
				requestFields(
					fieldWithPath("eventType").description("<code>subscription.status_changed</code> 또는"
						+ " <code>callback.registration_verification</code> 2종"),
					fieldWithPath("eventVersion").description("고정 <code>1.0</code>"),
					fieldWithPath("occurredAt").description("발생 시각(timezone 없는 ISO-8601)."
						+ " <b>순서 판단의 유일한 근거</b> — 이미 반영한 것보다 과거면 무시된다. 비어 올 수 있다"),
					fieldWithPath("orderId").description("최초 구독 주문 식별자 — <b>구독을 찾는 유일한 키</b>."
						+ " 모르는 주문이면 무시한다(웹훅으로 구독을 만들지 않는다)"),
					fieldWithPath("sku").description("상품 코드"),
					fieldWithPath("changeReason").description("변경 사유 12종. <b>분기하지 않는다</b> — 로그용"),
					fieldWithPath("subscription.previous").optional()
						.description("변경 전 스냅샷. <b>생략될 수 있다</b>(CREATED 등). 우리 상태와 다르면 웹훅 유실로 기록한다"),
					fieldWithPath("subscription.previous.status").optional().description("변경 전 상태"),
					fieldWithPath("subscription.previous.accessGranted").optional()
						.description("<b>쓰지 않는다</b> — 산식이 공개돼 있지 않다"),
					fieldWithPath("subscription.previous.expiresAt").optional().description("변경 전 만료 시각"),
					fieldWithPath("subscription.previous.autoRenew").optional().description("변경 전 자동 갱신 여부"),
					fieldWithPath("subscription.current").description("변경 후 스냅샷 — <b>반영의 유일한 근거</b>"),
					fieldWithPath("subscription.current.status")
						.description("ACTIVE · IN_GRACE_PERIOD · ON_HOLD · PAUSED · EXPIRED · REVOKED 6종"),
					fieldWithPath("subscription.current.accessGranted")
						.description("<b>쓰지 않는다</b> — 개폐는 서버가 판정한다"),
					fieldWithPath("subscription.current.expiresAt").optional()
						.description("만료 시각. <b>null 일 수 있다</b> — 비면 기존 값을 유지한다"),
					fieldWithPath("subscription.current.autoRenew").optional()
						.description("자동 갱신 예정 여부. 비면 기존 값을 유지한다"))));

		verify(subscriptionStatusPort).handleWebhook(any(), any(WebhookEvent.class));
	}

	@Test
	@DisplayName("S12 — 콜백 URL 등록 검증 이벤트도 204 로 수신한다")
	void 등록_검증_수신() throws Exception {
		mockMvc.perform(post(WEBHOOK_PATH)
				.header(SubscriptionWebhookController.SECRET_HEADER, HEADER)
				.contentType(MediaType.APPLICATION_JSON)
				.content(REGISTRATION_BODY))
			.andExpect(status().isNoContent())
			.andDo(document("subscription-webhook-registration",
				requestPreprocessor(), responsePreprocessor(),
				requestFields(
					fieldWithPath("eventType").description("<code>callback.registration_verification</code>"),
					fieldWithPath("eventVersion").description("고정 <code>1.0</code>"))));
	}

	@Test
	@DisplayName("S12 — 시크릿이 다르면 401 AUTH_002 로 거부한다")
	void 시크릿_불일치() throws Exception {
		mockMvc.perform(post(WEBHOOK_PATH)
				.header(SubscriptionWebhookController.SECRET_HEADER, "Basic Zm9yZ2VkOmZvcmdlZA==")
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.name()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_002.getMessage()))
			.andExpect(jsonPath("$.orderId").doesNotExist())
			.andDo(document("subscription-webhook-fail-forged",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code")
						.description("AUTH_002 — Basic Auth 값이 일치하지 않는다. 반영하지 않고 거부한다"),
					fieldWithPath("message").description("안내 문구"))));
	}

	@Test
	@DisplayName("S12 — Authorization 헤더가 없으면 400 이 아니라 401 AUTH_002 다")
	void 시크릿_누락() throws Exception {
		mockMvc.perform(post(WEBHOOK_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.content(STATUS_CHANGED_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.name()))
			.andDo(document("subscription-webhook-fail-no-secret",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("AUTH_002 — Authorization 헤더가 없다. 형식 오류(400)가 아니라 인증 실패다"),
					fieldWithPath("message").description("안내 문구"))));
	}
}
