package kang20.ytcreator.payment.internal.service.support;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import kang20.ytcreator.shared.support.Support;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Support
@Slf4j
@RequiredArgsConstructor
public class OrderLedgerWriter {
	private final OrderRepository orderRepository;

	@Transactional
	public Order record(GrantRequest request, UserId userId) {
		return orderRepository.save(Order.grant(request, userId));
	}
}
