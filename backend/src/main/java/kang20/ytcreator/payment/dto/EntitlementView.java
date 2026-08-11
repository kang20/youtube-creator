package kang20.ytcreator.payment.dto;

import java.time.OffsetDateTime;

/**
 * U5 이용권 상태(payment-design.md §5-3). <b>{@code accessible} 은 서버가 계산해 내려준다</b> —
 * 프론트는 이 값 하나로 분기한다(K10). <b>{@code orderId} 를 담지 않는다</b>(U14) —
 * 클라는 SDK 로 직접 얻는다(payment.md §5-7).
 *
 * @param accessible        지금 쓸 수 있는가 = 기간권이 살아 있다 OR 횟수권 잔량 > 0
 * @param credits           횟수권 잔량
 * @param subscriptionStale 구독 상태 미확인(§4-7-1③) — true 면 프론트가 recheck 흐름을 탄다(K11)
 * @param subscription      구독 요약 — 이력 없음도 {@code status=NONE} 정상 응답이다(404 아님)
 */
public record EntitlementView(boolean accessible, int credits, boolean subscriptionStale,
		SubscriptionView subscription) {

	/**
	 * 구독 상태 요약. {@code expiresAt} 은 저장(LocalDateTime·KST 해석)과 달리
	 * <b>직렬화 시점에 +09:00 오프셋을 붙인다</b>(payment-design.md §12-4 권고안).
	 *
	 * @param status    구독 상태 6종 또는 {@code NONE}(이력 없음)
	 * @param expiresAt 만료 예정(+09:00). 이력 없으면 null
	 * @param autoRenew 자동 갱신 여부. 이력 없으면 false
	 */
	public record SubscriptionView(String status, OffsetDateTime expiresAt, boolean autoRenew) {

		/** 구독 이력 없음 표기(§5-3). */
		public static final String STATUS_NONE = "NONE";

		public static SubscriptionView none() {
			return new SubscriptionView(STATUS_NONE, null, false);
		}
	}
}
