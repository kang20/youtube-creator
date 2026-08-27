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

	/**
	 * 콘솔의 "Basic Auth 헤더" 설정이 이 헤더로 온다 — 헤더 이름을 우리가 고를 수 없다.
	 * JWT 게이트와 같은 헤더지만 충돌하지 않는다: {@code JwtAuthenticationFilter} 는 {@code Bearer }
	 * 가 아닌 값을 거부하지 않고 통과시키고, 이 경로는 permitAll 이다.
	 */
	public static final String SECRET_HEADER = HttpHeaders.AUTHORIZATION;

	private final SubscriptionStatusPort subscriptionStatusPort;

	/** {@code required = false} 인 이유: 헤더 부재도 <b>인증 실패</b>로 다뤄야 한다(400 이 아니라 401). */
	@PostMapping("/subscription")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void receive(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
			@RequestBody WebhookEvent event) {
		subscriptionStatusPort.handleWebhook(secret, event);
	}
}
