package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

/**
 * 구독(SUBSCRIPTION) 주문이 지급 확정됐다는 사실 — 구독 계약 한 행을 열 근거다
 * (new-domain/payment.md "주문과 구독은 최초 지급 시 함께 만들어져야 한다").
 *
 * <p>발행은 원장 삽입에 <b>이긴</b> 요청만 한다({@code OrderLedgerWriter.record}) — 재요청에서
 * 또 발행하면 구독이 두 번 열리려 시도한다.
 *
 * <p>⚠️ 수신이 동기 {@code @EventListener} 라 직렬화되지 않는다({@link ConsumableGranted} 와 같다).
 * 리스너를 {@code @ApplicationModuleListener} 로 되돌리면 {@link UserId}·{@link OrderId} 에 Jackson 이
 * 읽을 프로퍼티가 없어 {@code {"userId":{},"orderId":{}}} 가 저장된다 — 그때는 페이로드를 원시
 * 타입({@code long}·{@code String})으로 바꿔야 한다.
 *
 * @param orderId 로그 추적용이 아니다 — <b>수신 측(subscription)이 저장한다.</b> 자연키 복제가 아니라
 *                웹훅이 구독을 찾아오는 유일한 상관관계 식별자로서의 정당한 보관이다(플랫폼이 웹훅에
 *                {@code subscriptionId} 를 주지 않는다). 타입으로 넘기므로 마스킹 보장이 값과 함께
 *                모듈 경계를 넘는다
 */
public record SubscriptionGranted(UserId userId, OrderId orderId) {
}
