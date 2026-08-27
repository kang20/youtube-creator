package kang20.ytcreator.payment.internal.service;

import static kang20.ytcreator.shared.exception.ErrorCode.*;

import java.util.Optional;

import org.springframework.stereotype.Service;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import kang20.ytcreator.payment.internal.service.support.OrderLedgerWriter;
import kang20.ytcreator.payment.internal.service.support.OrderVerifier;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.support.UniqueRace;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentPurchasePort {
	private final OrderRepository orderRepository;
	private final OrderVerifier orderVerifier;
	private final OrderLedgerWriter ledgerWriter;

	@Override
	public GrantResult grant(UserId userId, OrderId orderId) {
		Optional<Order> order = orderRepository.findByOrderId(orderId);
		if (order.isPresent()) {
			return replayOrReject(order.get(), userId);
		}

		GrantRequest request = orderVerifier.verify(orderId);

		return UniqueRace.firstWriterWins(
			() -> {
				ledgerWriter.record(request, userId);

				return new GrantResult(true, request.productType());
			},
			() -> orderRepository.findByOrderId(orderId)
				.map(winner -> replayOrReject(winner, userId)), orderId);
	}

	private GrantResult replayOrReject(Order order, UserId userId) {
		if (!order.ownedBy(userId)) {
			throw new BusinessException(PAY_005);
		}
		return new GrantResult(true, order.getProductType());
	}
}
