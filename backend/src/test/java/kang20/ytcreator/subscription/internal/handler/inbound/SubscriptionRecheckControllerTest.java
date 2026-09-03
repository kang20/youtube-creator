package kang20.ytcreator.subscription.internal.handler.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.ControllerTest;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@WebMvcTest(SubscriptionRecheckController.class)
class SubscriptionRecheckControllerTest extends ControllerTest {

	private static final String RECHECK_PATH = "/api/v1/subscriptions/recheck";

	private static final UserId USER_ID = new UserId(1L);

	private static final String BODY = """
		{
		  "orderId": "sub-order-1",
		  "status": "ACTIVE",
		  "expiresAt": "2026-09-06T00:00:00",
		  "autoRenew": true
		}
		""";

	@MockitoBean
	private SubscriptionStatusPort subscriptionStatusPort;

	@Test
	@DisplayName("S16 — 재확인은 204 로 답한다 — 본문 없음. 보정할 것이 없어도 204 다")
	void 재확인() throws Exception {
		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content(BODY))
			.andExpect(status().isNoContent())
			.andDo(document("subscription-recheck",
				requestPreprocessor(), responsePreprocessor(),
				requestHeaders(headerWithName(HttpHeaders.AUTHORIZATION)
					.description("Bearer access 토큰 — <b>이 토큰의 사용자 구독만</b> 보정된다."
						+ " 본문으로 사용자를 지목할 수 없다")),
				requestFields(
					fieldWithPath("orderId").description("보정할 구독의 주문 식별자."
						+ " SDK <code>getSubscriptionInfo</code> 를 부를 때 <b>넘긴 그 값</b>이다"
						+ " — 응답에는 없으므로 주문 생성 시점에 받은 값을 보관해 쓴다."
						+ " <b>남의 주문을 보내도 오류가 아니라 무동작(204)</b> 이다"),
					fieldWithPath("status").description("SDK <code>getSubscriptionInfo</code> 가 준 구독 상태 6종."
						+ " 모르는 값은 400 COMMON_001"),
					fieldWithPath("expiresAt").optional()
						.description("만료 예정(timezone 없는 ISO-8601 — 웹훅과 같은 표기)."
							+ " <b>null 허용</b> — 비면 서버가 아는 값을 유지한다"),
					fieldWithPath("autoRenew").description("자동 갱신 예정 여부. 필수"))));

		verify(subscriptionStatusPort).recheck(eq(USER_ID), any(SubscriptionSnapshot.class));
	}

	@Test
	@DisplayName("S16 — 모르는 상태 값이면 400 COMMON_001 이다")
	void 모르는_상태_값() throws Exception {
		doThrow(new BusinessException(ErrorCode.COMMON_001))
			.when(subscriptionStatusPort).recheck(eq(USER_ID), any(SubscriptionSnapshot.class));

		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "orderId": "sub-order-1", "status": "SOMETHING_ELSE", "expiresAt": null, "autoRenew": true }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.COMMON_001.name()))
			.andDo(document("subscription-recheck-fail-unknown-status",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code")
						.description("COMMON_001 — 구독 상태 6종에 없는 값이다. 재시도해도 같으니 SDK 응답을 확인한다"),
					fieldWithPath("message").description("안내 문구"))));
	}

	@Test
	@DisplayName("S16 — orderId·status 가 비었거나 autoRenew 가 없으면 400 COMMON_001 이다")
	void 요청_검증() throws Exception {
		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "orderId": "sub-order-1", "status": "", "expiresAt": "2026-09-06T00:00:00", "autoRenew": true }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.COMMON_001.name()))
			.andDo(document("subscription-recheck-fail-invalid",
				requestPreprocessor(), responsePreprocessor(),
				responseFields(
					fieldWithPath("code").description("COMMON_001 — 필수 값이 비어 있다(형식 축)"),
					fieldWithPath("message").description("안내 문구"))));

		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "orderId": "sub-order-1", "status": "ACTIVE", "expiresAt": "2026-09-06T00:00:00" }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.COMMON_001.name()));

		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "status": "ACTIVE", "expiresAt": "2026-09-06T00:00:00", "autoRenew": true }
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.COMMON_001.name()));
	}

	@Test
	@DisplayName("S16 — expiresAt 이 null 이어도 204 다 — 비면 서버가 아는 만료를 유지한다")
	void 만료가_비어_있어도_수용() throws Exception {
		mockMvc.perform(post(RECHECK_PATH)
				.header(HttpHeaders.AUTHORIZATION, bearer(USER_ID))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{ "orderId": "sub-order-1", "status": "IN_GRACE_PERIOD", "expiresAt": null, "autoRenew": false }
					"""))
			.andExpect(status().isNoContent());

		verify(subscriptionStatusPort).recheck(eq(USER_ID),
			eq(new SubscriptionSnapshot("sub-order-1", "IN_GRACE_PERIOD", (LocalDateTime) null, false)));
	}
}
