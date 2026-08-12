package kang20.ytcreator.auth.internal.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.internal.entity.RefreshToken;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.shared.support.Support;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh 토큰 쓰기 전용 빈 — 발급·원자 회전·재사용 전체 폐기(auth-design.md §14-2·§14-4).
 *
 * <p><b>{@code @Transactional} 은 여기에만 있다</b> — {@code AuthService} 는 트랜잭션을 열지 않는다
 * (§6-4 전제 유지). 원자성은 트랜잭션 격리가 아니라 <b>조건부 UPDATE 의 영향 행 수</b>가 담당한다(§14-4).
 *
 * <p><b>원문을 저장하지 않는다</b>(U10) — 발급 시 SecureRandom 256bit → Base64url 원문을 호출자에게
 * 돌려주고, DB 에는 SHA-256 hex 64자만 남긴다. 해시는 조회 키라 솔트 없이 결정적이다
 * ({@code AnonymousKeyHasher} 와 같은 논리 — support 는 support 를 못 부르므로 계산을 여기 둔다).
 */
@Component
@Support
public class RefreshTokenWriter {

	/** refresh 토큰 수명 — auth.md U7 확정(14일). 만료가 누적 행을 자연 정리한다(§14-6). */
	private static final int REFRESH_TOKEN_DAYS = 14;

	/** 원문 엔트로피 — 256bit(32바이트) → Base64url 43자. */
	private static final int TOKEN_BYTES = 32;

	private static final String HASH_ALGORITHM = "SHA-256";

	private static final HexFormat HEX = HexFormat.of();

	private final RefreshTokenRepository refreshTokenRepository;

	private final SecureRandom secureRandom = new SecureRandom();

	public RefreshTokenWriter(RefreshTokenRepository refreshTokenRepository) {
		this.refreshTokenRepository = refreshTokenRepository;
	}

	/**
	 * 새 refresh 를 발급한다 — 저장은 해시뿐이고 <b>원문은 이 반환값이 유일하다</b>(U10).
	 *
	 * @param now 발급 시각 — 만료는 {@code now + 14일}
	 * @return refresh 원문(Base64url)
	 */
	@Transactional
	public String issue(UserId userId, LocalDateTime now) {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		refreshTokenRepository.save(
			new RefreshToken(userId, hash(rawToken), now.plusDays(REFRESH_TOKEN_DAYS)));

		return rawToken;
	}

	/**
	 * 원자 회전(§14-4) — {@code revoked_at IS NULL} 조건부 UPDATE 한 방. 영향 행 수가 곧 판정이다:
	 * 1 = 승자(새 쌍을 받는다), 0 = 동시 갱신 경쟁 패배(호출자가 AUTH_005 로 거부한다).
	 * 락도 격리 수준 가정도 없다 — H2·MySQL 동일.
	 */
	@Transactional
	public int rotate(String tokenHash, LocalDateTime now) {
		return refreshTokenRepository.revokeIfActive(tokenHash, now);
	}

	/**
	 * 재사용 감지 시 전체 폐기(U9) — 탈취 신호로 보고 그 사용자의 활성 refresh 를 전부 죽인다.
	 * 정당한 사용자도 로그아웃되지만 익명키 재로그인 비용이 0 이라 손해가 없다(auth.md §5-5).
	 */
	@Transactional
	public int revokeAllByUserId(UserId userId, LocalDateTime now) {
		return refreshTokenRepository.revokeAllByUserId(userId, now);
	}

	/**
	 * 저장·조회용 해시 — SHA-256 소문자 hex 64자. 결정적이어야 조회 키가 된다.
	 *
	 * <p>⚠️ 예외 메시지에 원문을 넣지 마라(U10 — U6 와 같은 규율).
	 */
	public String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
			return HEX.formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// 도달 불가 — SHA-256 은 JDK 명세상 모든 JVM 이 제공한다.
			throw new IllegalStateException(HASH_ALGORITHM + " 알고리즘을 찾을 수 없다", e);
		}
	}
}
