package kang20.ytcreator.subscription.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import kang20.ytcreator.subscription.dto.WebhookEvent;

public interface SubscriptionStatusPort {

	void handleWebhook(String secret, WebhookEvent event);

	void recheck(UserId userId, SubscriptionSnapshot fromClient);
}
