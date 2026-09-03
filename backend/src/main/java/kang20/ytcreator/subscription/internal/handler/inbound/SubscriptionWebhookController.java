package kang20.ytcreator.subscription.internal.handler.inbound;

import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/toss")
@RequiredArgsConstructor
public class SubscriptionWebhookController {

	public static final String SECRET_HEADER = HttpHeaders.AUTHORIZATION;

	private final SubscriptionStatusPort subscriptionStatusPort;

	@PostMapping("/subscription")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void receive(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
			@RequestBody WebhookEvent event) {
		subscriptionStatusPort.handleWebhook(secret, event);
	}
}
