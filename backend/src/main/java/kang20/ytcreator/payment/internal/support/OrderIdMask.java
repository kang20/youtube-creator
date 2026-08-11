package kang20.ytcreator.payment.internal.support;

/**
 * 주문 식별자 로그 마스킹 — U14(비노출)의 payment 자체 정책.
 *
 * <p><b>{@code AnonymousKeyFormat.mask} 를 재사용하지 않는다</b>(코드 리뷰 🟡-2) —
 * 그건 익명키(U6) 전용 정책이라, auth 쪽 마스킹 폭이 바뀌면 payment 의 U14 노출 폭이
 * 조용히 함께 바뀐다. 같은 "앞 4자" 방식이지만 정책의 주인이 다르다(payment-design §9).
 */
public final class OrderIdMask {

	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private OrderIdMask() {
	}

	/** 앞 4자만 남긴다. 4자 미만이면 전부 가린다 — 어떤 입력에서도 원문이 새지 않는다. */
	public static String mask(String orderId) {
		if (orderId == null || orderId.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return orderId.substring(0, VISIBLE_PREFIX) + MASK;
	}
}
