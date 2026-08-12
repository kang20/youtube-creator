/**
 * 결제·이용권 — 토스 IAP 주문을 우리 이용권으로 바꾸고, "지금 쓸 수 있는가"에 답한다
 * (payment.md U1~U14 · payment-design.md).
 *
 * <p>이 모듈은 <b>"이 사용자가 무엇을 쓸 수 있는가"</b>에 단일 권위를 갖는다 — 사용자가 누구인지도,
 * 작업이 무엇인지도 모른다(§2). 식별에 쓰는 것은 auth 가 노출한
 * {@link kang20.ytcreator.auth.UserId} 뿐이며, <b>익명키를 저장·식별에 쓰지 않는다</b> —
 * 익명키 수신은 {@code internal.handler.inbound.PaymentController} 한 곳뿐이고 즉시
 * {@code UserId} 로 해석해 버린다(§2-1 쟁점 1).
 *
 * <p>모듈 밖 진입은 <b>루트의 {@code *Port} 인터페이스가 전부</b>다(2026-08-12 재조직) —
 * {@link kang20.ytcreator.payment.PaymentReaderPort}(조회 — bootstrap·자기 컨트롤러)·
 * {@link kang20.ytcreator.payment.PaymentConsumePort}(소모 — subtitle 계약)·
 * {@link kang20.ytcreator.payment.PaymentPurchasePort}(구매)·
 * {@link kang20.ytcreator.payment.PaymentWebhookPort}(웹훅) 넷이다.
 * 구현({@code internal.service.PaymentService})은 이 네 포트를 구현하며 <b>직접 참조 불가</b>다 —
 * 컨트롤러조차 포트로만 부른다. {@code @Support} 부품은 {@code internal.service.support} 에 있고
 * Service 만 참조한다(architecture.md "Port·Service·Support 규약").
 * 밖에 노출되는 식별자는 {@link kang20.ytcreator.payment.UsageTicketId} 하나다(subtitle 이 쓴다 — §3).
 *
 * <p><b>이벤트를 발행하지 않는다</b> — MVP 에 구독할 모듈이 하나도 없다(§2).
 * {@code auth → payment} 역방향 참조는 영원히 금지다(§2-1 쟁점 4).
 */
@ApplicationModule(displayName = "결제·이용권", allowedDependencies = {"shared", "auth", "auth :: dto"})
package kang20.ytcreator.payment;

import org.springframework.modulith.ApplicationModule;
