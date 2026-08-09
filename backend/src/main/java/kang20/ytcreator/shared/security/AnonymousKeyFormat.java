package kang20.ytcreator.shared.security;

import org.springframework.util.StringUtils;

/**
 * 익명키 형식 규칙과 마스킹.
 *
 * <p>규칙은 최소선(공백 아님 + 길이 상한)뿐이다 — 실제 문자셋을 모른 채 좁히면 정상 사용자를 막는다.
 */
public final class AnonymousKeyFormat {

	/**
	 * 들어오는 <b>원문</b>의 길이 상한. 잠정값 — 규격은 auth.md 🔶-3 에서 확정된다.
	 *
	 * <p>⚠️ <b>저장 길이(해시 64)와 다른 축이다.</b> 64 로 줄이면 정상 익명키가 전부 AUTH_002 로
	 * 거부된다(auth-design.md §12-2).
	 */
	public static final int MAX_LENGTH = 255;

	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private AnonymousKeyFormat() {
	}

	public static boolean isValid(String raw) {
		return StringUtils.hasText(raw) && raw.length() <= MAX_LENGTH;
	}

	/**
	 * 로그·진단용 마스킹(U6). 앞 {@value #VISIBLE_PREFIX} 자만 남긴다.
	 *
	 * <p>앞자리보다 짧은 입력은 남길 것이 곧 원문이므로 <b>아무것도 남기지 않는다.</b>
	 */
	public static String mask(String raw) {
		if (raw == null || raw.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return raw.substring(0, VISIBLE_PREFIX) + MASK;
	}
}
