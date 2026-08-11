package kang20.ytcreator.payment.dto;

/**
 * 판매 상품 유형(payment-design.md §3). 판별 근거는 <b>토스 응답의 sku</b> 다 —
 * 클라 주장을 믿지 않는다(§5-2③).
 */
public enum ProductType {

	/** 단건 소모품 — 지급 시 횟수권 잔량 +1(§6-5). */
	CONSUMABLE,

	/** 월 구독 — 지급 시 기간권 1행 생성(§6-5). */
	SUBSCRIPTION
}
