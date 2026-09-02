/**
 * 결제·이용권 — "누가 무엇을 쓸 수 있는가"에 권위를 갖는다(new-domain/payment.md).
 *
 * <p><b>돈은 토스가 받는다.</b> 이 모듈이 하는 일은 결제된 주문을 우리 이용권으로 바꾸고(지급),
 * 그 이용권을 쓸 수 있는지 판정하고(이용 게이트), 쓴 만큼 줄이는 것(소모)이다.
 *
 * <p><b>애그리거트는 4개</b>(주문 · 구독 · 횟수권 잔량 · 이용 티켓)이고 서로 참조하지 않는다 —
 * 연결고리는 {@code UserId} 와 주문 식별자 값뿐이다. 변경 트리거가 서로 달라서이며,
 * 하나로 합치면 동시성 대책이 전부 한 행에 몰린다.
 *
 * <p><b>공개 표면은 이벤트 두 개와 {@code OrderId}(+ 컨버터), {@code PaymentUsagePort} 다</b> —
 * 지급을 부르는 것은 자기 컨트롤러뿐이라 {@code PaymentPurchasePort} 는 {@code internal/port} 에
 * 있다(architecture.md "공개 표면", R1). 지급은 다른 모듈이 <b>호출하지 않고 구독하며</b>, 이용
 * (소모·확정·해제)만 subtitle 이 {@code PaymentUsagePort} 로 부른다 — 구현체가 오기 전까지는
 * 전부 거부하는 임시 어댑터({@code PaymentUsageService})가 조립된다(subtitle-v3 이용권 연동).
 *
 * <p>⚠️ <b>이 모듈이 구현한 애그리거트는 주문 하나다</b>(2026-08-14 재설계). {@code PaymentService} 가
 * {@code internal.port.PaymentPurchasePort} 를 구현하는 도메인 모듈이라
 * {@code ArchitectureConventionTest} 의
 * 규약(R1~R7) 대상이다. 이용 티켓 애그리거트는 아직 없고, 나머지 둘은 다른 모듈이 소유한다 —
 * <b>횟수권 잔량은 {@code credit}, 구독은 {@code subscription}</b>. 지급이 확정되면 상품 유형에 따라
 * {@code ConsumableGranted} 또는 {@code SubscriptionGranted} 를 발행하고, 각 모듈이 그 이벤트를
 * 구독해 자기 애그리거트를 만든다. 재요청(replay)은 어느 쪽도 발행하지 않는다 — 멱등.
 *
 * <p>⚠️ 두 이벤트의 {@code orderId} 직렬화 방식이 <b>다르다</b> — credit 은 로그 추적용이라 마스킹,
 * subscription 은 웹훅 조회 키라 원문이다. 근거는 각 record 의 javadoc 에 있다.
 *
 * <p>⚠️ <b>{@code auth} 에서 쓰는 것은 {@code UserId} 하나다.</b> {@code User} 엔티티는 모른다 —
 * {@code payment → auth} 단방향이 불변식이다.
 */
@ApplicationModule(displayName = "결제·이용권", allowedDependencies = {"shared", "auth"})
package kang20.ytcreator.payment;

import org.springframework.modulith.ApplicationModule;
