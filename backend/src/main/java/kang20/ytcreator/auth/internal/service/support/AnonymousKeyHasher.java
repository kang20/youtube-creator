package kang20.ytcreator.auth.internal.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kang20.ytcreator.shared.support.Support;

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
