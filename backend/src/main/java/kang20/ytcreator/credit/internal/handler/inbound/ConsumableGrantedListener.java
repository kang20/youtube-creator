package kang20.ytcreator.credit.internal.handler.inbound;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.credit.internal.port.CreditGrantPort;
import kang20.ytcreator.payment.ConsumableGranted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
