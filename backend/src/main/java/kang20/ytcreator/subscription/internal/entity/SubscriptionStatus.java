package kang20.ytcreator.subscription.internal.entity;

import java.util.Optional;

public enum SubscriptionStatus {

	ACTIVE,

	IN_GRACE_PERIOD,

	ON_HOLD,

	PAUSED,

	EXPIRED,

	REVOKED;

	public static Optional<SubscriptionStatus> from(String raw) {
		for (SubscriptionStatus status : values()) {
			if (status.name().equals(raw)) {
				return Optional.of(status);
			}
		}
		return Optional.empty();
	}
}
