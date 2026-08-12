package kang20.ytcreator.payment.internal.entity;


/**
 * 토스 구독 상태 6종(payment-design.md §3 · iap-essentials §3-5).
 *
 * <p>⚠️ <b>상태 어휘를 게이트에 그대로 쓰지 않는다</b> — "구독이 어떤 상태인가"와
 * "열어줄 것인가"는 별개다. 개폐 판정은 {@code SubscriptionGate} 가 한다(§4-3).
 */
public enum SubscriptionStatus {
	ACTIVE,
	IN_GRACE_PERIOD,
	ON_HOLD,
	PAUSED,
	EXPIRED,
	REVOKED
}
