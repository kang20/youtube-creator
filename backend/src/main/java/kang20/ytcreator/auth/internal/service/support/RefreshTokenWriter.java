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
import org.springframework.transaction.annotation.Transactional;

@Support
public class RefreshTokenWriter {

	private static final int REFRESH_TOKEN_DAYS = 14;

	private static final int TOKEN_BYTES = 32;

	private static final String HASH_ALGORITHM = "SHA-256";

	private static final HexFormat HEX = HexFormat.of();

	private final RefreshTokenRepository refreshTokenRepository;

	private final SecureRandom secureRandom = new SecureRandom();

	public RefreshTokenWriter(RefreshTokenRepository refreshTokenRepository) {
		this.refreshTokenRepository = refreshTokenRepository;
	}

	@Transactional
	public String issue(UserId userId, LocalDateTime now) {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		refreshTokenRepository.save(
			new RefreshToken(userId, hash(rawToken), now.plusDays(REFRESH_TOKEN_DAYS)));

		return rawToken;
	}

	@Transactional
	public int rotate(String tokenHash, LocalDateTime now) {
		return refreshTokenRepository.revokeIfActive(tokenHash, now);
	}

	@Transactional
	public int revokeAllByUserId(UserId userId, LocalDateTime now) {
		return refreshTokenRepository.revokeAllByUserId(userId, now);
	}

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
