package kang20.ytcreator.payment.internal.service.support;

import static kang20.ytcreator.shared.exception.ErrorCode.*;

import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.support.Support;
import lombok.RequiredArgsConstructor;

@Support
@RequiredArgsConstructor
public class OrderVerifier {
	private final TossOrderClient tossOrderClient;
	private final ProductCatalog catalog;

	public GrantRequest verify(OrderId orderId) {
		TossOrderStatus toss = tossOrderClient.statusOf(orderId);
		if (!toss.available()) {
			throw new BusinessException(PAY_006);
		}
		if (!toss.grantable()) {
			throw new BusinessException(toss.rejection());
		}

		ProductType productType = catalog.typeOf(toss.sku())
			.orElseThrow(() -> new BusinessException(PAY_004));

		return new GrantRequest(orderId, toss.sku(), productType);
	}
}
