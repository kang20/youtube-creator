package kang20.ytcreator.subscription.internal.handler.inbound;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.payment.SubscriptionGranted;
import kang20.ytcreator.subscription.internal.port.SubscriptionGrantPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 🔴 <b>동기 {@code @EventListener} 다.</b> 발행자의 원장 트랜잭션 안에서 돌기 때문에 구독 개시와
 * 원장 삽입이 함께 커밋/롤백된다 — <b>여기서 던진 예외가 지급 API 를 롤백시킨다.</b>
 *
 * <p>⚠️ <b>예외를 삼키지 않는다.</b> 항구적 거부({@code SUB_001})가 있던 시절에는 영원한 재시도를
 * 막으려 삼켰지만, 재구독을 허용하면서 그 거부가 사라졌다. 지금 남은 실패는 전부 일시적이라
 * 삼키면 <b>돈은 받고 구독은 없는</b> 상태가 조용히 남는다.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SubscriptionGrantedListener {
	private final SubscriptionGrantPort subscriptionGrantPort;

	@EventListener
	public void on(SubscriptionGranted event) {
		subscriptionGrantPort.start(event.userId(), event.orderId());
		log.info("[subscription] 구독 개시 — userId={}, orderId={}", event.userId(), event.orderId());
	}
}
