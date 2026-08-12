package kang20.ytcreator.payment;

import kang20.ytcreator.payment.dto.WebhookEvent;

/**
 * 결제·이용권 <b>웹훅 포트</b> — 토스가 보내는 구독 상태 변경·등록 검증 수신(payment-design.md §5-4).
 * 소비자: payment 자신의 웹훅 컨트롤러(inbound driving port).
 *
 * <p>익명키 게이트 밖(permitAll)이라 모듈이 Basic Auth 로 다시 막는다(U11) — 인증은 구현체가 한다.
 * 컨트롤러가 구현체를 직접 참조하지 못하게 하려고 포트로 노출한다.
 */
public interface PaymentWebhookPort {

	/**
	 * U9·U10·U11·U13 웹훅 처리(§5-4) — 검증 통과 후에는 어떤 경우에도 성공(204)이다.
	 * 반영 실패가 수신 성공을 막지 않는다(재전송 정책 부재).
	 */
	void handleWebhook(String authorizationHeader, WebhookEvent event);
}
