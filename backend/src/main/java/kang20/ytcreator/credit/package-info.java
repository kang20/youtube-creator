/**
 * 횟수권 — 단건 결제로 얻는 1회 이용권의 <b>잔량</b>에 권위를 갖는다
 * (new-domain/payment.md 횟수권 애그리거트).
 *
 * <p>쓰기 진입은 하나다 — payment 가 발행한 {@code ConsumableGranted} 를 리스너
 * ({@code internal/handler/inbound})가 받아 {@code internal.port.CreditGrantPort} 로 잔량을 1 올린다.
 * 수신은 <b>동기</b>라 잔량 증가와 원장 삽입이 한 덩어리로 커밋/롤백된다.
 *
 * <p><b>이 모듈의 공개 표면은 비어 있다</b> — 포트를 부르는 것은 자기 리스너뿐이라 {@code internal/port}
 * 에 있다(architecture.md "공개 표면", R1). 밖에서 부를 소비자가 생기면 그때 모듈 루트로 올린다.
 *
 * <p>⚠️ <b>소모({@code minus})·조회 포트·읽기 모델은 아직 없다</b> — 호출자(이용 티켓 애그리거트·
 * bootstrap 집계)가 아직 없다. 소비자 없는 코드는 만들지 않는다(architecture.md 포트 선례).
 *
 * <p>⚠️ <b>주문 식별자를 저장하지 않는다</b> — 자연키 복제 금지. 이벤트의 {@code orderId} 는
 * 로그 추적용이다. "같은 주문으로 두 번 오르지 않는다"는 이 모듈이 아니라 발행 측
 * (payment 원장의 멱등)이 지킨다.
 *
 * <p>{@code payment} 의존은 이벤트 record({@code ConsumableGranted}) 참조 한정이다 — 행위 호출이
 * 아니다. {@code auth} 에서 쓰는 것은 {@code UserId} 하나다.
 */
@ApplicationModule(displayName = "횟수권", allowedDependencies = {"shared", "auth", "payment"})
package kang20.ytcreator.credit;

import org.springframework.modulith.ApplicationModule;
