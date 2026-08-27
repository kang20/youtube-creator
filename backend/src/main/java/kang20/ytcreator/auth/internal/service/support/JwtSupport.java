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

/**
 * access 토큰(JWT · HS256) 발급·파싱·서명 검증 — jjwt 0.12.x (auth-design.md §14-2).
 * 클레임은 {@code sub}(=userId)·{@code iat}·{@code exp}(발급 +30분)뿐이다(auth.md §5-1).
 *
 * <p><b>서명 키는 환경 변수로만 주입한다</b>({@code ytcreator.auth.jwt.secret}) — 커밋·로그 금지.
 * 미설정이면 <b>기동 실패</b>다(fail-fast — mTLS 게이트와 같은 규율). 열려 있는 채 뜨는 것보다 낫다.
 *
 * <p>발급 시각·만료 판정 모두 주입받은 {@link Clock} 을 쓴다 — 파서의 시계도 같은 빈에 물려
 * 테스트에서 만료를 재현할 수 있다.
 */
@Support
public class JwtSupport {

	/** access 토큰 수명 — auth.md U7 확정(30분). */
	private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);

	/** 권한 클레임 이름. ⚠️ 바꾸면 유통 중인 토큰이 전부 USER 로 강등된다({@link Role#from}). */
	private static final String ROLE_CLAIM = "role";

	private final SecretKey key;
	private final Clock clock;
	private final JwtParser parser;

	public JwtSupport(@Value("${ytcreator.auth.jwt.secret:}") String secret, Clock clock) {
		if (!StringUtils.hasText(secret)) {
			// ⚠️ 예외 메시지에 키 값을 넣지 마라 — 미설정 사실만 알린다
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

	/** 발급 — 권한 생략은 {@link Role#USER} 다. 대부분의 호출자가 여기다. */
	public String issue(UserId userId) {
		return issue(userId, Role.USER);
	}

	/**
	 * 발급 — sub 는 {@link UserId} 의 원시값 문자열, {@code role} 은 {@link Role} 이름이다.
	 *
	 * <p>권한을 토큰에 싣는 이유는 U8 — 매 요청 DB 조회 없이 인가하려면 서명된 클레임이어야 한다.
	 * 대가로 <b>권한 변경이 access 수명(30분)만큼 늦게 반영된다</b>. 즉시 반영이 필요하면
	 * 그건 클레임이 아니라 DB 게이트를 붙이는 별개의 결정이다.
	 */
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

	/**
	 * 서명·만료 검증 후 요청 주체와 권한을 돌려준다(U8 — DB 조회 없음).
	 *
	 * @throws io.jsonwebtoken.ExpiredJwtException 만료 — AUTH_004 축(프론트가 refresh 한다)
	 * @throws io.jsonwebtoken.JwtException        위조·형식 불량 — AUTH_002 축(재시도 무익)
	 * @throws IllegalArgumentException            null·빈 토큰 — AUTH_002 축
	 */
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
