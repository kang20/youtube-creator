package kang20.ytcreator.payment.internal.handler.outbound.client;

import kang20.ytcreator.shared.exception.ErrorCode;

public record TossOrderStatus(boolean available, OrderStatus status, String sku) {

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

	public boolean grantable() {
		return status == OrderStatus.PURCHASED || status == OrderStatus.PAYMENT_COMPLETED;
	}

	public ErrorCode rejection() {
		return switch (status) {
			case ORDER_IN_PROGRESS -> ErrorCode.PAY_002;
			case FAILED, REFUNDED -> ErrorCode.PAY_003;
			case NOT_FOUND, MINIAPP_MISMATCH -> ErrorCode.PAY_004;
			default -> ErrorCode.PAY_006;
		};
	}
}
