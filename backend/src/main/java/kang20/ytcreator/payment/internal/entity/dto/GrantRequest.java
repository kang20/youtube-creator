package kang20.ytcreator.payment.internal.entity.dto;

import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.OrderId;

/**
 * 검증이 끝난 주문 — {@code OrderVerifier} 의 산출물이고 지급의 근거다.
 *
 * <p>세 값이 <b>함께 정해지고 함께 넘어간다.</b> 전부 토스 응답에서 나오며, 낱개로 넘기면
 * {@code sku}(문자열)가 위치로만 구분돼 인자 순서가 바뀌어도 컴파일러가 잡지 못한다.
 *
 * <p>⚠️ <b>소유자를 담지 않는다.</b> 누가 받을지는 검증의 관심사가 아니라 기록의 관심사다 —
 * {@code OrderLedgerWriter.record} 가 {@code UserId} 를 따로 받아 합류시킨다. 여기 넣으면
 * 검증에 쓰이지도 않는 값을 검증기가 통과시켜야 한다.
 *
 * <p>⚠️ <b>모듈 밖으로 나가지 않는다.</b> 밖으로 나가는 것은 {@code payment/dto} 의 결과 타입뿐이다.
 *
 * @param orderId     토스 주문 식별자
 * @param sku         <b>토스가 답한</b> 상품 코드. 클라이언트 주장이 아니다
 * @param productType {@code sku} 를 카탈로그로 판별한 결과
 */
public record GrantRequest(OrderId orderId, String sku, ProductType productType) {
}
