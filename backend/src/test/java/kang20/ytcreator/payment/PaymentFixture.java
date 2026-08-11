package kang20.ytcreator.payment;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.WebhookEvent;

/**
 * payment 테스트 데이터 표준화 (docs/rule/testing.md "Fixture 클래스로 테스트 데이터 표준화").
 *
 * <p>{@code UserId} 는 시퀀스로 만든다 — 물리 FK 가 없으므로(payment-design.md §3-1) users 행
 * 없이도 유효하고, 테스트끼리 사용자·주문이 겹치지 않는 것이 격리의 근거다.
 *
 * <p>⚠️ orderId 는 명백한 더미를 쓴다(U14 — 문서·로그 어디서든 실제처럼 보이는 값 금지).
 */
public final class PaymentFixture {

	/** 테스트 카탈로그 sku — @TestPropertySource 의 값과 짝이다. */
	public static final String ONE_TIME_SKU = "sku-one-time";
	public static final String SUBSCRIPTION_SKU = "sku-subscription";

	/** 카탈로그 밖 sku — 남의 상품(§5-2③ → PAY_004). */
	public static final String FOREIGN_SKU = "sku-not-ours";

	/** 웹훅 Basic Auth — @TestPropertySource 의 자격과 짝이다. */
	public static final String WEBHOOK_USER = "toss-webhook";
	public static final String WEBHOOK_PASSWORD = "webhook-secret";
	public static final String WEBHOOK_AUTH_HEADER =
		"Basic " + java.util.Base64.getEncoder().encodeToString(
			(WEBHOOK_USER + ":" + WEBHOOK_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));

	/** 모든 시간 단언의 기준 시각(KST). */
	public static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 11, 12, 0, 0);

	private static final AtomicLong USER_SEQ = new AtomicLong(1_000);
	private static final AtomicLong ORDER_SEQ = new AtomicLong();

	private PaymentFixture() {
	}

	/** 테스트마다 다른 사용자 — 행 격리의 근거. */
	public static UserId uniqueUser() {
		return new UserId(USER_SEQ.incrementAndGet());
	}

	/** 명백한 더미 orderId(U14). 라벨로 어느 테스트의 주문인지 추적한다. */
	public static String uniqueOrder(String label) {
		return "order-" + label + "-" + ORDER_SEQ.incrementAndGet();
	}

	/** 구독 상태 변경 웹훅 — current 만 있는 최소 페이로드(§5-5). */
	public static WebhookEvent statusChanged(String orderId, LocalDateTime occurredAt, String changeReason,
			WebhookEvent.Snapshot current) {
		return statusChanged(orderId, occurredAt, changeReason, null, current);
	}

	/** 구독 상태 변경 웹훅 — previous 포함(유실 감지 축 §4-7). */
	public static WebhookEvent statusChanged(String orderId, LocalDateTime occurredAt, String changeReason,
			WebhookEvent.Snapshot previous, WebhookEvent.Snapshot current) {
		return new WebhookEvent("subscription.status_changed", "1.0", occurredAt, orderId,
			SUBSCRIPTION_SKU, changeReason, new WebhookEvent.SubscriptionChange(previous, current));
	}

	/** U10 등록 검증 이벤트 — eventType·occurredAt 뿐(§5-5 — challenge 없음). */
	public static WebhookEvent registrationVerification(LocalDateTime occurredAt) {
		return new WebhookEvent(WebhookEvent.TYPE_REGISTRATION_VERIFICATION, "1.0", occurredAt,
			null, null, null, null);
	}

	public static WebhookEvent.Snapshot snapshot(String status, LocalDateTime expiresAt, Boolean autoRenew) {
		return new WebhookEvent.Snapshot(status, null, expiresAt, autoRenew);
	}
}
