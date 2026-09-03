package kang20.ytcreator.subscription.internal.handler.inbound;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.payment.SubscriptionGranted;
import kang20.ytcreator.subscription.internal.port.SubscriptionGrantPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
