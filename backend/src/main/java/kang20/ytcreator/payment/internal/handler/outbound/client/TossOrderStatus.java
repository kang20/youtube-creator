package kang20.ytcreator.payment.internal.handler.outbound.client;

import kang20.ytcreator.shared.exception.ErrorCode;

/**
 * 토스 {@code get-order-status} 응답의 우리 쪽 표현(payment-design.md §5-2 · iap-essentials §3-3~3-4).
 *
 * <p>응답은 2단 봉투다 — {@code resultType} 이 {@code SUCCESS} 가 아니면 전부 실패로 본다
 * ({@code available=false} → PAY_006). 비즈니스 오류가 HTTP 200 으로 오므로 HTTP 상태로
 * 성공 판정하면 안 된다.
 *
 * @param available resultType == SUCCESS 이고 success 본문이 있다
 * @param status    주문 상태 8종 — available=false 면 null
 * @param sku       상품 SKU — MINIAPP_MISMATCH·NOT_FOUND·ERROR 에서는 안 온다(null)
 */
public record TossOrderStatus(boolean available, OrderStatus status, String sku) {

	/** 주문 상태 8종. 미문서화 값은 {@link #parse} 가 ERROR 로 접는다. */
	public enum OrderStatus {
		PURCHASED,
		PAYMENT_COMPLETED,
		ORDER_IN_PROGRESS,
		FAILED,
		REFUNDED,
		NOT_FOUND,
		MINIAPP_MISMATCH,
		ERROR
	}

	/** 봉투 실패(FAIL·HTTP_TIMEOUT 등 6종)·전송 실패·비활성 — 전부 PAY_006 경로다(§5-2). */
	public static TossOrderStatus unavailable() {
		return new TossOrderStatus(false, null, null);
	}

	public static TossOrderStatus of(String status, String sku) {
		return new TossOrderStatus(true, parse(status), sku);
	}

	private static OrderStatus parse(String raw) {
		if (raw == null) {
			return OrderStatus.ERROR;
		}
		try {
			return OrderStatus.valueOf(raw);
		} catch (IllegalArgumentException unknownStatus) {
			// 미문서화 status 추가에 방어 — 비정상(ERROR)과 같은 PAY_006 경로로 접는다
			return OrderStatus.ERROR;
		}
	}

	/**
	 * 지급 대상 판정(✅-1). ⚠️ {@code PAYMENT_COMPLETED} 를 지급 대상으로 보는 것은
	 * <b>문서 근거가 없는 확정</b>이라 이 분기를 <b>이 한 줄에 몰아 놓는다</b> —
	 * 뒤집히면 여기만 고친다(§5-2 · §12-2).
	 */
	public boolean grantable() {
		return status == OrderStatus.PURCHASED || status == OrderStatus.PAYMENT_COMPLETED;
	}

	/** 지급 불가 status → 에러 코드 매핑(§5-2②). {@code grantable()} 일 때 부르면 안 된다. */
	public ErrorCode rejection() {
		return switch (status) {
			case ORDER_IN_PROGRESS -> ErrorCode.PAY_002;
			case FAILED, REFUNDED -> ErrorCode.PAY_003;
			case NOT_FOUND, MINIAPP_MISMATCH -> ErrorCode.PAY_004;
			default -> ErrorCode.PAY_006;
		};
	}
}
