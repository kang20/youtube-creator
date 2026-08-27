package kang20.ytcreator.credit.internal.handler.inbound;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.credit.internal.port.CreditGrantPort;
import kang20.ytcreator.payment.ConsumableGranted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 🔴 <b>동기 {@code @EventListener} 다.</b> 발행자의 원장 트랜잭션 안에서 돌기 때문에 잔량 증가와
 * 원장 삽입이 함께 커밋/롤백된다 — 유실을 복구가 아니라 부재로 막는 대신, <b>여기서 던진 예외가
 * 지급 API 를 롤백시킨다.</b> 항구적 거부를 삼켜야 할 이유가 생기면 여기서 끊어야 한다
 * ({@code SubscriptionGrantedListener} 선례).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConsumableGrantedListener {
	private final CreditGrantPort creditGrantPort;

	@EventListener
	public void on(ConsumableGranted event) {
		creditGrantPort.grant(event.userId());
		log.info("[credit] 단건 지급 반영 — 잔량 +1. userId={}, orderId={}", event.userId(), event.orderId());
	}
}
