package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;

/**
 * 결제·이용권 <b>구매 포트</b> — 클라이언트가 결제/구독 후 상태를 서버에 반영시키는 쓰기 흐름
 * (payment-design.md §4). 소비자: payment 자신의 결제 컨트롤러(inbound driving port).
 *
 * <p>모듈 공개 계약이지만 <b>실질 소비자는 이 모듈의 컨트롤러</b>다 — 다른 모듈이 부를 일은 없다
 * (구매는 클라 SDK 왕복이 전제). 컨트롤러가 구현체를 직접 참조하지 못하게 하려고 포트로 노출한다.
 */
public interface PaymentPurchasePort {

	/**
	 * U2·U3·U4·U12 지급(§5-2) — 멱등. 토스 검증 후 이용권을 지급한다.
	 * ⚠️ 구현체는 {@code @Transactional} 없이 동작한다(§6-2 함정 ③·④).
	 */
	GrantResult grant(UserId userId, String orderId);

	/**
	 * §4-7-1⑥ STALE 해소(§5-5) — 클라가 보낸 구독 스냅샷으로 게이트를 재개방한다.
	 * 🔴 {@code fromClient} 는 클라 값이다(알고 여는 구멍 — §12-3, 출시 전 닫는다).
	 */
	EntitlementView recheck(UserId userId, SubscriptionSnapshot fromClient);
}
