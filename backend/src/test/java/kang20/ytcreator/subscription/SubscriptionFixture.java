package kang20.ytcreator.subscription;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;

final class SubscriptionFixture {

	static final OrderId ORDER = new OrderId("sub-fixture-order");

	static final UserId USER = new UserId(7001L);

	private SubscriptionFixture() {
	}

	static Subscription active(LocalDateTime expiresAt) {
		return Subscription.start(ORDER, USER, expiresAt);
	}

	static Subscription of(SubscriptionStatus status, LocalDateTime expiresAt) {
		Subscription subscription = active(expiresAt);
		subscription.applyClientSnapshot(status, expiresAt, true);
		return subscription;
	}

	static WebhookEvent statusChanged(String rawOrderId, LocalDateTime occurredAt,
			WebhookEvent.Snapshot previous, WebhookEvent.Snapshot current) {
		return new WebhookEvent("subscription.status_changed", "1.0", occurredAt, rawOrderId,
			"test.subscription", "RENEWED", new WebhookEvent.SubscriptionChange(previous, current));
	}

	static WebhookEvent.Snapshot snapshot(SubscriptionStatus status, LocalDateTime expiresAt, Boolean autoRenew) {
		return new WebhookEvent.Snapshot(status.name(), true, expiresAt, autoRenew);
	}
}
