package kang20.ytcreator.payment.dto;

import kang20.ytcreator.payment.internal.entity.Subscription;

/**
 * U1 상품 목록 응답(payment.md §5-2 원문 스키마 · payment-design.md §5-1) — 상품별 <b>중첩 객체</b>다.
 * {@code productType} 이 클라가 어느 주문 함수를 쓸지 결정한다:
 * {@code CONSUMABLE → createOneTimePurchaseOrder} · {@code SUBSCRIPTION → createSubscriptionPurchaseOrder}.
 *
 * <p><b>가격을 담지 않는다</b> — SDK {@code displayAmount} 가 정본이다(payment.md U1).
 * 미노출 상품은 설정을 비우면 <b>키 전체가 {@code null}</b> 로 내려가고 프론트가 버튼을 숨긴다(payment.md §5-2).
 *
 * @param oneTime      단건 소모품 — 미노출이면 null
 * @param subscription 월 구독 — 미노출이면 null
 */
public record ProductCatalog(OneTime oneTime, Subscription subscription) {

	/**
	 * @param sku         상품 고유 ID — 프론트가 {@code getProductItemList()} 결과에서 찾아 가격을 표시한다
	 * @param productType 항상 {@code CONSUMABLE} — {@code createOneTimePurchaseOrder} 로 주문한다
	 */
	public record OneTime(String sku, String productType) {
	}

	/**
	 * @param sku          상품 고유 ID
	 * @param productType  항상 {@code SUBSCRIPTION} — {@code createSubscriptionPurchaseOrder} 로 주문한다
	 * @param renewalCycle 구독 갱신 주기 표기({@code MONTHLY} — 30일 고정, 달력 월 아님)
	 * @param offerId      프로모션 오퍼 — MVP 는 항상 null(기본 가격)
	 */
	public record Subscription(String sku, String productType, String renewalCycle, String offerId) {
	}
}
