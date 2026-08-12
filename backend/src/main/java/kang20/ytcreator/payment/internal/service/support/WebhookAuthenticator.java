package kang20.ytcreator.payment.internal.service.support;
import kang20.ytcreator.shared.support.Support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 웹훅 Basic Auth 검증(U11 · payment-design.md §2-1 쟁점 3). 경로는 {@code SecurityConfig} 가
 * 열되(permitAll) <b>모듈이 스스로 인증한다</b> — 두 번째 SecurityFilterChain 을 만들지 않는다.
 *
 * <p>불일치 → 호출자가 {@code AUTH_002}(401) 로 던지고 <b>처리하지 않는다.</b>
 * 자격이 설정에 없으면 <b>항상 거부</b>한다 — 열려 있는 채 뜨는 것보다 낫다.
 *
 * <p>⚠️ 위조 웹훅의 최대 피해를 줄이는 것은 이 인증이 아니라 §5-5 의 "구독을 새로 만들지 않는다"
 * 규칙이다(✅-5). Basic Auth 는 1차 방어일 뿐이다.
 */
@Component
@Support
public class WebhookAuthenticator {

	/** 기대하는 {@code Authorization} 헤더 전문. 자격 미설정이면 null(항상 거부). */
	private final byte[] expectedHeader;

	public WebhookAuthenticator(
			@Value("${ytcreator.payment.webhook.username:}") String username,
			@Value("${ytcreator.payment.webhook.password:}") String password) {
		if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
			String token = Base64.getEncoder()
				.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
			this.expectedHeader = ("Basic " + token).getBytes(StandardCharsets.UTF_8);
		} else {
			this.expectedHeader = null;
		}
	}

	/** 상수 시간 비교 — 타이밍으로 자격이 새지 않게 한다. */
	public boolean verify(String authorizationHeader) {
		if (expectedHeader == null || authorizationHeader == null) {
			return false;
		}
		return MessageDigest.isEqual(expectedHeader, authorizationHeader.getBytes(StandardCharsets.UTF_8));
	}
}
