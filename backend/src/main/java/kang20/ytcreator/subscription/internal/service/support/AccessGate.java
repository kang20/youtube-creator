package kang20.ytcreator.subscription.internal.service.support;

import java.time.Duration;
import java.time.LocalDateTime;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;

@Support
public class AccessGate {

	private static final Duration EXPIRY_BUFFER = Duration.ofDays(3);

	public boolean accessible(Subscription subscription, LocalDateTime now) {
		if (subscription == null) {
			return false;
		}
		return switch (subscription.getStatus()) {
			case ACTIVE -> !bufferedExpiryPassed(subscription, now);
			case IN_GRACE_PERIOD -> true;
			case ON_HOLD, PAUSED, EXPIRED, REVOKED -> false;
		};
	}

	public boolean stale(Subscription subscription, LocalDateTime now) {
		return subscription != null
			&& subscription.getStatus() == SubscriptionStatus.ACTIVE
			&& bufferedExpiryPassed(subscription, now);
	}

	private boolean bufferedExpiryPassed(Subscription subscription, LocalDateTime now) {
		return now.isAfter(subscription.getExpiresAt().plus(EXPIRY_BUFFER));
	}
}
