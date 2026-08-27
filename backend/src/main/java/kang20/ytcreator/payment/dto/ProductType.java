package kang20.ytcreator.payment.dto;

/**
 * 상품 유형 — 지급이 무엇을 만들어내는지(new-domain/payment.md 주문 애그리거트).
 *
 * <p>토스 상품 타입 어휘를 그대로 쓴다. 플랫폼이 {@code NON_CONSUMABLE} 도 주지만
 * <b>우리 카탈로그에 없다</b> — 모르는 상품 코드는 남의 상품이라 지급하지 않는다.
 *
 * <p>지급 결과({@link GrantResult})에 실려 나가므로 dto 로 노출한다 — 프론트가 "1회권을 받았다"와
 * "구독이 시작됐다"를 구분해 화면을 바꾼다.
 */
public enum ProductType {

	/** 단건 — 지급하면 횟수권이 1 오른다. */
	CONSUMABLE,

	/** 구독 — 지급하면 구독 계약이 생긴다. */
	SUBSCRIPTION
}
