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
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U7(발급)·U8(서명·만료 검증) — access 토큰 규격. auth-design.md §14-5 {@code JwtSupportTest}
 * (라운드트립 · 만료 Clock 고정 · 서명 위조 거부 · 키 미설정 fail-fast).
 *
 * <p>스프링 없이 순수 단위로 돈다 — 시간은 {@link MutableClock} 으로 고정·이동한다
 * (docs/rule/testing.md "시간·랜덤은 주입").
 *
 * <p>⚠️ JWT 의 시각 클레임은 <b>초 단위</b>다(NumericDate) — 기준 시각을 초 정밀도로 잡아
 * 반올림 여지를 없앤다. 만료 경계 자체(정확히 exp 순간)는 jjwt 내부 판정이라 단언하지 않고,
 * 수명 ±1분에서 유효/만료를 가른다.
 */
class JwtSupportTest {

	/** 32바이트 이상 — HS256 최소 키 길이(round-1-dev.md 테스터 전제). */
	private static final String SECRET = "unit-test-hs256-secret-0123456789abcdef";

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 12, 12, 0, 0);

	private static final UserId USER = new UserId(42L);

	private final MutableClock clock = new MutableClock(BASE);

	private final JwtSupport jwtSupport = new JwtSupport(SECRET, clock);

	@BeforeEach
	void resetClock() {
		clock.setTo(BASE);
	}

	/** U7·U8 — 발급→파싱 라운드트립: sub 로 넣은 userId 가 그대로 돌아온다(DB 없이 신원 확정). */
	@Test
	@DisplayName("발급한 토큰을 파싱하면 같은 userId 가 돌아온다")
	void 라운드트립() {
		String token = jwtSupport.issue(USER);

		assertThat(token).isNotBlank();
		assertThat(jwtSupport.parse(token)).isEqualTo(USER);
	}

	/** U7 — 수명 30분: 발급 29분 뒤는 아직 유효하다(만료 테스트의 대조군). */
	@Test
	@DisplayName("발급 29분 뒤에는 아직 유효하다")
	void 만료_전은_유효하다() {
		String token = jwtSupport.issue(USER);

		clock.setTo(BASE.plusMinutes(29));

		assertThat(jwtSupport.parse(token)).isEqualTo(USER);
	}

	/**
	 * U7·U8 · auth.md §4-2 — 발급 +30분이 지나면 {@code ExpiredJwtException} 이다.
	 * 게이트는 이 예외를 <b>AUTH_004</b> 로 번역한다(무효 AUTH_002 와 다른 축 —
	 * 섞이면 30분마다 전 사용자가 재로그인한다).
	 */
	@Test
	@DisplayName("발급 31분 뒤에는 ExpiredJwtException 이다 — AUTH_004 축")
	void 만료() {
		String token = jwtSupport.issue(USER);

		clock.setTo(BASE.plusMinutes(31));

		assertThatThrownBy(() -> jwtSupport.parse(token))
			.isInstanceOf(ExpiredJwtException.class);
	}

	/**
	 * U8 — <b>다른 키로 서명된 토큰은 거부된다.</b> 이게 뚫리면 아무나 userId 를 지어내
	 * 남의 신원으로 전 API 를 쓴다(게이트는 DB 를 안 보므로 서명이 유일한 방어선이다).
	 */
	@Test
	@DisplayName("다른 키로 서명한 토큰은 SignatureException 으로 거부된다 — AUTH_002 축")
	void 서명_위조() {
		JwtSupport forger = new JwtSupport("forged-hs256-secret-0123456789abcdef!!", clock);
		String forged = forger.issue(USER);

		assertThatThrownBy(() -> jwtSupport.parse(forged))
			.isInstanceOf(SignatureException.class);
	}

	/** U8 — 본문(클레임)만 바꿔치기한 토큰도 서명 불일치로 죽는다(변조 방어). */
	@Test
	@DisplayName("본문을 변조한 토큰도 거부된다")
	void 본문_변조() {
		String[] parts = jwtSupport.issue(USER).split("\\.");
		String tampered = parts[0] + ".eyJzdWIiOiI5OTkifQ." + parts[2];   // sub=999 로 바꿔치기

		assertThatThrownBy(() -> jwtSupport.parse(tampered))
			.isInstanceOf(JwtException.class);
	}

	/** U8 — JWT 형식도 아닌 값·빈 값. 게이트에서 전부 AUTH_002 축이다. */
	@Test
	@DisplayName("JWT 형식이 아닌 값과 빈 값은 파싱 단계에서 거부된다")
	void 형식_불량() {
		assertThatThrownBy(() -> jwtSupport.parse("not-a-jwt"))
			.isInstanceOf(JwtException.class);
		assertThatThrownBy(() -> jwtSupport.parse(""))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * 서명은 유효한데 {@code sub} 가 숫자가 아닌 토큰 — 우리 발급기가 만들 수 없는 값이므로
	 * "우리가 발급한 토큰이 아니다"로 취급해 {@code MalformedJwtException}(AUTH_002 축)이다.
	 * 키가 새지 않는 한 도달하지 않지만, 도달했을 때 500 이 아니라 401 이어야 한다.
	 */
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

	/**
	 * §14-2 — <b>키 미설정 = 기동 실패</b>(fail-fast, mTLS 게이트와 같은 규율).
	 * 열려 있는 채 뜨는 것보다 낫다. 예외 메시지에 키 값이 실리지 않는 것도 함께 본다.
	 */
	@Test
	@DisplayName("서명 키가 비어 있으면 생성 자체가 실패한다 — fail-fast")
	void 키_미설정_fail_fast() {
		for (String missing : new String[] {"", "   ", null}) {
			assertThatThrownBy(() -> new JwtSupport(missing, clock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ytcreator.auth.jwt.secret");
		}
	}

	/** §14-2 — 256bit 미만의 약한 키도 기동 실패다(jjwt {@code WeakKeyException} — 의도된 동작). */
	@Test
	@DisplayName("32바이트 미만의 약한 키도 생성이 실패한다")
	void 약한_키도_fail_fast() {
		assertThatThrownBy(() -> new JwtSupport("too-short-key", clock))
			.isInstanceOf(WeakKeyException.class);
	}
}
