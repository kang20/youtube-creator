package kang20.ytcreator.payment.dto;

/**
 * U2·U3 지급 응답(payment-design.md §5-2⑤). ⚠️ 커밋 이후에 만든다 — {@code entitlement} 는
 * 지급이 반영된 스냅샷이다(payment.md §4-5-1ⓒ).
 *
 * @param granted     이 주문이 지급된 상태다 — 멱등 재요청(U3·U12 복원)도 true 다. 실패는 예외로 나간다
 * @param productType 지급된 상품 유형 — 토스 sku 로 판별된 값(클라 주장 아님). JSON 키는 payment.md §5-4 원문
 * @param entitlement 지급 반영 후의 현재 이용권 상태
 */
public record GrantResult(boolean granted, ProductType productType, EntitlementView entitlement) {
}
