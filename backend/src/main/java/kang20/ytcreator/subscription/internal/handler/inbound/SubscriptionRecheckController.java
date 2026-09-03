package kang20.ytcreator.subscription.internal.handler.inbound;

import jakarta.validation.Valid;
import kang20.ytcreator.auth.CurrentUser;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SubscriptionRecheckController {
	private final SubscriptionStatusPort subscriptionStatusPort;

	@PostMapping("/api/v1/subscriptions/recheck")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void recheck(@CurrentUser UserId userId, @Valid @RequestBody SubscriptionSnapshot fromClient) {
		subscriptionStatusPort.recheck(userId, fromClient);
	}
}
