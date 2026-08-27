package kang20.ytcreator.subscription.internal.service.support;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Support
@RequiredArgsConstructor
public class SubscriptionStartWriter {
	private final SubscriptionRepository subscriptionRepository;

	// 해당 트랜잭션의 상위 트랜잭션은 Payment 모듈의 record 이다.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Subscription open(Subscription subscription) {
		return subscriptionRepository.saveAndFlush(subscription);
	}
}
