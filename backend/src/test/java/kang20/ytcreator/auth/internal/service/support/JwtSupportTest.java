package kang20.ytcreator.auth.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.jsonwebtoken.security.WeakKeyException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtSupportTest {

	private static final String SECRET = "unit-test-hs256-secret-0123456789abcdef";

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 12, 12, 0, 0);

	private static final UserId USER = new UserId(42L);

	private final MutableClock clock = new MutableClock(BASE);

	private final JwtSupport jwtSupport = new JwtSupport(SECRET, clock);

	@BeforeEach
	void resetClock() {
		clock.setTo(BASE);
	}

	@Test
	@DisplayName("발급한 토큰을 파싱하면 같은 userId 가 돌아온다")
	void 라운드트립() {
		String token = jwtSupport.issue(USER);

		assertThat(token).isNotBlank();
		assertThat(jwtSupport.parse(token).userId()).isEqualTo(USER);
	}

	@Test
	@DisplayName("발급한 권한이 파싱 결과로 그대로 돌아온다")
	void 권한_라운드트립() {
		assertThat(jwtSupport.parse(jwtSupport.issue(USER, Role.ADMIN)).role()).isEqualTo(Role.ADMIN);
		assertThat(jwtSupport.parse(jwtSupport.issue(USER, Role.USER)).role()).isEqualTo(Role.USER);
	}

	@Test
	@DisplayName("권한을 지정하지 않고 발급하면 USER 다")
	void 권한_기본값() {
		assertThat(jwtSupport.parse(jwtSupport.issue(USER)).role()).isEqualTo(Role.USER);
	}

	@Test
	@DisplayName("role 클레임이 없는 토큰은 USER 로 읽는다 — 권한 확대 방향으로 실패하지 않는다")
	void role_클레임이_없으면_USER_다() {
		String legacy = Jwts.builder()
			.subject("42")
			.issuedAt(Date.from(clock.instant()))
			.expiration(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
			.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
			.compact();

		assertThat(jwtSupport.parse(legacy).role()).isEqualTo(Role.USER);
	}

	@Test
	@DisplayName("모르는 role 값은 USER 로 떨어진다")
	void 모르는_role_은_USER_다() {
		String alien = Jwts.builder()
			.subject("42")
			.claim("role", "SUPERUSER")
			.issuedAt(Date.from(clock.instant()))
			.expiration(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
			.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
			.compact();

		assertThat(jwtSupport.parse(alien).role()).isEqualTo(Role.USER);
	}

	@Test
	@DisplayName("발급 29분 뒤에는 아직 유효하다")
	void 만료_전은_유효하다() {
		String token = jwtSupport.issue(USER);

		clock.setTo(BASE.plusMinutes(29));

		assertThat(jwtSupport.parse(token).userId()).isEqualTo(USER);
	}

	@Test
	@DisplayName("발급 31분 뒤에는 ExpiredJwtException 이다 — AUTH_004 축")
	void 만료() {
		String token = jwtSupport.issue(USER);

		clock.setTo(BASE.plusMinutes(31));

		assertThatThrownBy(() -> jwtSupport.parse(token))
			.isInstanceOf(ExpiredJwtException.class);
	}

	@Test
	@DisplayName("다른 키로 서명한 토큰은 SignatureException 으로 거부된다 — AUTH_002 축")
	void 서명_위조() {
		JwtSupport forger = new JwtSupport("forged-hs256-secret-0123456789abcdef!!", clock);
		String forged = forger.issue(USER);

		assertThatThrownBy(() -> jwtSupport.parse(forged))
			.isInstanceOf(SignatureException.class);
	}

	@Test
	@DisplayName("본문을 변조한 토큰도 거부된다")
	void 본문_변조() {
		String[] parts = jwtSupport.issue(USER).split("\\.");
		String tampered = parts[0] + ".eyJzdWIiOiI5OTkifQ." + parts[2];   // sub=999 로 바꿔치기

		assertThatThrownBy(() -> jwtSupport.parse(tampered))
			.isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("JWT 형식이 아닌 값과 빈 값은 파싱 단계에서 거부된다")
	void 형식_불량() {
		assertThatThrownBy(() -> jwtSupport.parse("not-a-jwt"))
			.isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> jwtSupport.parse(""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("서명이 유효해도 sub 가 숫자가 아니면 MalformedJwtException 이다")
	void sub_가_숫자가_아니면_거부한다() {
		String alien = Jwts.builder()
			.subject("not-a-number")
			.issuedAt(Date.from(clock.instant()))
			.expiration(Date.from(clock.instant().plus(Duration.ofMinutes(5))))
			.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
			.compact();

		assertThatThrownBy(() -> jwtSupport.parse(alien))
			.isInstanceOf(MalformedJwtException.class);
	}

	@Test
	@DisplayName("서명 키가 비어 있으면 생성 자체가 실패한다 — fail-fast")
	void 키_미설정_fail_fast() {
		for (String missing : new String[] {"", "   ", null}) {
			assertThatThrownBy(() -> new JwtSupport(missing, clock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ytcreator.auth.jwt.secret");
		}
	}

	@Test
	@DisplayName("32바이트 미만의 약한 키도 생성이 실패한다")
	void 약한_키도_fail_fast() {
		assertThatThrownBy(() -> new JwtSupport("too-short-key", clock))
			.isInstanceOf(WeakKeyException.class);
	}
}
