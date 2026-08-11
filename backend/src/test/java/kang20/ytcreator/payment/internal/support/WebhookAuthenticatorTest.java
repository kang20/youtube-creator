package kang20.ytcreator.payment.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U11 웹훅 Basic Auth 검증 단위 (payment-design.md §10 — 일치/불일치/헤더 없음 + 안전 기본값).
 *
 * <p>round-1-dev 판단 10 — 자격이 설정에 없으면 <b>항상 거부</b>한다(열려 있는 채 뜨는 것보다 낫다).
 */
class WebhookAuthenticatorTest {

	private static String basic(String username, String password) {
		return "Basic " + Base64.getEncoder()
			.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private final WebhookAuthenticator authenticator = new WebhookAuthenticator("toss", "secret");

	/** U11 — 콘솔에 등록한 자격과 일치할 때만 통과 */
	@Test
	@DisplayName("자격이 일치하는 헤더만 통과한다")
	void 일치만_통과() {
		assertThat(authenticator.verify(basic("toss", "secret"))).isTrue();
		assertThat(authenticator.verify(basic("toss", "wrong"))).isFalse();
		assertThat(authenticator.verify(basic("other", "secret"))).isFalse();
		assertThat(authenticator.verify("Bearer something")).isFalse();
	}

	/** U11 — 헤더 없음(null)도 거부다 */
	@Test
	@DisplayName("헤더가 없으면 거부한다")
	void 헤더_없음_거부() {
		assertThat(authenticator.verify(null)).isFalse();
	}

	/**
	 * 안전 기본값 — 자격 미설정(빈 값)이면 <b>어떤 헤더도</b> 통과하지 못한다.
	 * 미설정인 채 열려 뜨면 누구나 위조 웹훅으로 기간권을 흔들 수 있다(R6).
	 */
	@Test
	@DisplayName("자격이 설정에 없으면 항상 거부한다 — 열린 채 뜨지 않는다")
	void 미설정이면_항상_거부() {
		WebhookAuthenticator unconfigured = new WebhookAuthenticator("", "");
		WebhookAuthenticator halfConfigured = new WebhookAuthenticator("toss", "");

		assertThat(unconfigured.verify(basic("toss", "secret"))).isFalse();
		assertThat(unconfigured.verify(null)).isFalse();
		assertThat(halfConfigured.verify(basic("toss", ""))).isFalse();
	}
}
