package kang20.ytcreator.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record SubscriptionSnapshot(@NotBlank String orderId, @NotBlank String status,
		LocalDateTime expiresAt, @NotNull Boolean autoRenew) {
}
