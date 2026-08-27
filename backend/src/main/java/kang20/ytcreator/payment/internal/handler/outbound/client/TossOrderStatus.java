package kang20.ytcreator.payment.internal.handler.outbound.client;

import kang20.ytcreator.shared.exception.ErrorCode;

/**
 * 토스 {@code get-order-status} 응답의 우리 쪽 표현
 * (new-domain/payment.md 참고자료 ① — 2026-08-14 원문 확인).
 *
 * <p>🔴 <b>응답은 2단 봉투이고 비즈니스 오류가 HTTP 200 으로 온다.</b> HTTP 상태로 성공을 판정하면
 * 안 된다 — {@code resultType} 이 {@code SUCCESS} 가 아니면 전부 실패({@code available=false})다.
 *
 * @param available {@code resultType == SUCCESS} 이고 {@code success} 본문이 있다
 * @param status    주문 상태 8종 — {@code available=false} 면 null
 * @param sku       상품 코드 — {@code MINIAPP_MISMATCH}·{@code NOT_FOUND}·{@code ERROR} 에서는 안 온다
 */
public record TossOrderStatus(boolean available, OrderStatus status, String sku) {

	/** 주문 상태 8종. 미문서화 값은 {@link #parse} 가 {@code ERROR} 로 접는다. */
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

	/** 봉투 실패(6종)·전송 실패·비활성 — 전부 같은 경로다. 우리는 "주문을 확인하지 못했다"만 안다. */
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
			// 플랫폼이 status 를 추가해도 조용히 지급되지 않게 — 비정상과 같은 경로로 접는다
			return OrderStatus.ERROR;
		}
	}

	/**
	 * 지급 대상 판정.
	 *
	 * <p>⚠️ {@code PAYMENT_COMPLETED}("결제는 됐고 지급이 실패한 상태")를 지급 대상으로 보는 것은
	 * <b>우리 판단</b>이다. 문서가 "지급하라"고 말하지는 않는다. 뒤집힐 수 있으므로
	 * <b>이 한 줄에 몰아 놓는다</b> — 바뀌면 여기만 고친다.
	 */
	public boolean grantable() {
		return status == OrderStatus.PURCHASED || status == OrderStatus.PAYMENT_COMPLETED;
	}

	/** 지급 불가 상태 → 에러 코드. {@link #grantable()} 일 때 부르면 안 된다. */
	public ErrorCode rejection() {
		return switch (status) {
			case ORDER_IN_PROGRESS -> ErrorCode.PAY_002;
			case FAILED, REFUNDED -> ErrorCode.PAY_003;
			case NOT_FOUND, MINIAPP_MISMATCH -> ErrorCode.PAY_004;
			default -> ErrorCode.PAY_006;
		};
	}
}
