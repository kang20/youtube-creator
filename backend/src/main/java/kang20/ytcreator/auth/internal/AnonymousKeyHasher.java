package kang20.ytcreator.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 익명키를 저장·조회용 해시로 바꾼다. {@code SHA-256} → <b>소문자 hex 64자</b>.
 *
 * <p><b>왜 원문을 저장하지 않는가</b>(docs/domain/auth-design.md §3-2, blockers B4):
 * UNIQUE 위반을 정상 흐름으로 삼는 설계(§6-4)에서 위반이 나면 Hibernate·DB 가 제약 위반 메시지에
 * <b>중복된 값을 그대로 실어</b> WARN 으로 찍는다. 그 값이 원문이면 auth.md U6·§4-5 와
 * docs/ops/logging.md §3.3(anonKey 원문 절대 금지)을 어긴다. 저장 값을 해시로 바꾸면
 * 같은 메시지에 해시만 남는다.
 *
 * <p><b>솔트를 쓰지 않는다.</b> 이 해시는 비밀번호 해시가 아니라 <b>조회 키</b>다 —
 * 같은 익명키가 항상 같은 값이어야 UNIQUE 제약과 멱등(U2)이 성립한다.
 * 익명키 자체가 고엔트로피 값이라 사전 공격 대상도 아니다(§3-2).
 *
 * <p>원문은 이 클래스 밖으로 나가지 않는다 — 예외 메시지에도 넣지 않는다(U6).
 */
@Component
public class AnonymousKeyHasher {

	private static final String ALGORITHM = "SHA-256";

	/** {@link HexFormat#of()} 는 소문자 hex 를 만든다. SHA-256 은 32바이트라 항상 64자다. */
	private static final HexFormat HEX = HexFormat.of();

	public String hash(String rawKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
			return HEX.formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// 도달 불가 — SHA-256 은 모든 JVM 이 제공해야 하는 표준 알고리즘이다(JDK 명세).
			throw new IllegalStateException(ALGORITHM + " 알고리즘을 찾을 수 없다", e);
		}
	}
}
