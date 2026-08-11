package kang20.ytcreator.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * U2 지급 요청(payment-design.md §5-2). <b>sku 를 받지 않는다</b> — 상품 판별은
 * 토스 응답의 sku 로 한다. 클라 주장을 믿지 않는다(payment.md §5-4).
 *
 * @param orderId 토스 주문 ID — 저장 외 어디에도 노출하지 않는다(U14)
 */
public record GrantRequest(@NotBlank String orderId) {
}
