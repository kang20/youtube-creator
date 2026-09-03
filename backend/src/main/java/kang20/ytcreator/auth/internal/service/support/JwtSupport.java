package kang20.ytcreator.auth.internal.service.support;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.UserPrincipal;
import kang20.ytcreator.shared.support.Support;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Support
public class JwtSupport {

	private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);

	private static final String ROLE_CLAIM = "role";

	private final SecretKey key;
	private final Clock clock;
	private final JwtParser parser;

	public JwtSupport(@Value("${ytcreator.auth.jwt.secret:}") String secret, Clock clock) {
		if (!StringUtils.hasText(secret)) {
			// 예외 메시지에 키 값을 넣지 마라 — 미설정 사실만 알린다
			throw new IllegalStateException(
				"ytcreator.auth.jwt.secret 이 설정되지 않았다 — JWT 서명 키 없이는 기동하지 않는다"
					+ "(fail-fast, auth-design.md §14-2)");
		}
		// hmacShaKeyFor 는 256bit 미만 키를 WeakKeyException 으로 거부한다 — 약한 키도 기동 실패다
		this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
		this.clock = clock;
		this.parser = Jwts.parser()
			.verifyWith(key)
			.clock(() -> Date.from(clock.instant()))
			.build();
	}

	public String issue(UserId userId) {
		return issue(userId, Role.USER);
	}

	public String issue(UserId userId, Role role) {
		Instant now = clock.instant();
		return Jwts.builder()
			.subject(String.valueOf(userId.longValue()))
			.claim(ROLE_CLAIM, role.name())
			.issuedAt(Date.from(now))
			.expiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	public UserPrincipal parse(String accessToken) {
		Claims claims = parser.parseSignedClaims(accessToken).getPayload();
		try {
			return new UserPrincipal(
				new UserId(Long.valueOf(claims.getSubject())),
				Role.from(claims.get(ROLE_CLAIM, String.class)));
		} catch (NumberFormatException e) {
			// 서명이 유효한데 sub 가 숫자가 아닌 경우 — 우리가 발급한 토큰이 아니라는 뜻이다
			throw new MalformedJwtException("sub 클레임이 사용자 식별자가 아니다", e);
		}
	}
}
