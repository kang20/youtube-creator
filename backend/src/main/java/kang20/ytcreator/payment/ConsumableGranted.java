package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

/**
 * 단건(CONSUMABLE) 주문의 지급 확정 — 횟수권 잔량을 1 올릴 근거다.
 *
 * <p>발행은 원장 삽입에 <b>이긴</b> 요청만 한다({@code OrderLedgerWriter.record}) — 재요청에서
 * 또 발행하면 잔량이 두 번 올라 멱등이 깨진다.
 *
 * <p>⚠️ 수신이 동기 {@code @EventListener} 라 직렬화되지 않는다. 리스너를
 * {@code @ApplicationModuleListener} 로 되돌리면 {@link UserId}·{@link OrderId} 에 Jackson 이
 * 읽을 프로퍼티가 없어 {@code {"userId":{},"orderId":{}}} 가 저장된다 — 그때는 페이로드를 원시
 * 타입({@code long}·{@code String})으로 바꿔야 한다.
 *
 * @param orderId 로그 추적용이다 — 수신 측은 저장하지 않는다(자연키 복제 금지)
 */
public record ConsumableGranted(UserId userId, OrderId orderId) {
}
