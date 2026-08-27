package kang20.ytcreator.auth.internal.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kang20.ytcreator.shared.support.Support;

/**
 * 익명키 → 저장·조회용 해시. {@code SHA-256} 소문자 hex 64자. 근거: auth-design.md §3-2.
 *
 * <p><b>솔트를 쓰지 않는다.</b> 비밀번호 해시가 아니라 <b>조회 키</b>라 결정적이어야 UNIQUE·멱등이 성립한다.
 *
 * <p>⚠️ 예외 메시지에 원문을 넣지 마라(U6).
 */
@Support
public class AnonymousKeyHasher {

	private static final String ALGORITHM = "SHA-256";

	private static final HexFormat HEX = HexFormat.of();

	public String hash(String rawKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
			return HEX.formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// 도달 불가 — SHA-256 은 JDK 명세상 모든 JVM 이 제공한다.
			throw new IllegalStateException(ALGORITHM + " 알고리즘을 찾을 수 없다", e);
		}
	}
}
