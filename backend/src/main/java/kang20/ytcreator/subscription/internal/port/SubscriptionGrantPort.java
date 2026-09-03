package kang20.ytcreator.subscription.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;

public interface SubscriptionGrantPort {

	/** 호출자가 발행자의 원장 트랜잭션 안에 있는 동기 리스너라 진입점에 {@code @Transactional} 을 붙이지 않는다. */
	void start(UserId userId, OrderId orderId);
}
