package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

public record ConsumableGranted(UserId userId, OrderId orderId) {
}
