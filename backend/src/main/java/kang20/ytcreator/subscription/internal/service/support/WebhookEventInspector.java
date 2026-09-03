package kang20.ytcreator.subscription.internal.service.support;

import static kang20.ytcreator.shared.exception.ErrorCode.AUTH_002;
import static kang20.ytcreator.shared.exception.ErrorCode.COMMON_001;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Support
public class WebhookEventInspector {

	private static final String BASIC_PREFIX = "Basic ";

	private final byte[] expectedSecret;

	public WebhookEventInspector(@Value("${ytcreator.subscription.webhook.secret:}") String secret) {
		this.expectedSecret = StringUtils.hasText(secret) ? secret.getBytes(StandardCharsets.UTF_8) : null;
	}

	public boolean shouldApply(String authorization, WebhookEvent event) {
		if (!isAuthentic(authorization)) {
			throw new BusinessException(AUTH_002);
		}
		if (WebhookEvent.TYPE_REGISTRATION_VERIFICATION.equals(event.eventType())) {
			return false;
		}
		if (event.orderId() == null || event.subscription() == null
			|| event.subscription().current() == null) {
			throw new BusinessException(COMMON_001);
		}
		return true;
	}

	private boolean isAuthentic(String authorization) {
		if (expectedSecret == null || authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
			return false;
		}
		byte[] presented = authorization.substring(BASIC_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expectedSecret, presented);
	}
}
