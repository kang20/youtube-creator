package kang20.ytcreator.subscription.dto;

import java.time.LocalDateTime;

public record WebhookEvent(String eventType, String eventVersion, LocalDateTime occurredAt,
		String orderId, String sku, String changeReason, SubscriptionChange subscription) {

	public static final String TYPE_REGISTRATION_VERIFICATION = "callback.registration_verification";

	public record SubscriptionChange(Snapshot previous, Snapshot current) {
	}

	public record Snapshot(String status, Boolean accessGranted, LocalDateTime expiresAt, Boolean autoRenew) {
	}
}
