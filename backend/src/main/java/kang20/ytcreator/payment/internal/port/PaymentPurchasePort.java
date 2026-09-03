package kang20.ytcreator.payment.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.GrantResult;

public interface PaymentPurchasePort {

	/** {@code @Transactional} 밖에서 불러야 한다 — 토스 왕복이 커넥션을 물고, 재조회가 호출자 스냅샷에 갇힌다. */
	GrantResult grant(UserId userId, OrderId orderId);
}
