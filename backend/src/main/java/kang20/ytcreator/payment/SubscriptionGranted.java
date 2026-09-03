package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

public record SubscriptionGranted(UserId userId, OrderId orderId) {
}
