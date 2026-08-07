package kang20.ytcreator.shared.security;

import org.springframework.util.StringUtils;

/**
 * 익명키 형식 규칙과 마스킹. 상수 + 순수 함수만 있고 상태를 갖지 않는다.
 *
 * <p>규칙은 <b>"공백 아님 + 255자 이하"</b> 최소선뿐이다. 실제 hash 문자셋을 모른 채 문자셋을 좁히면
 * 정상 사용자를 막는다 — 스파이크로 규격이 확인되면 조인다(docs/domain/auth-design.md §12-2).
 */
public final class AnonymousKeyFormat {

	/**
	 * 들어오는 <b>원문</b> 익명키의 길이 상한이다.
	 *
	 * <p>⚠️ <b>저장 길이와는 무관하다.</b> 저장하는 값은 SHA-256 해시(64자 고정)이고 이 상수는
	 * <b>입력 원문</b>의 상한이라 두 값은 <b>같이 움직이지 않는다</b>(auth-design.md §3-2 · §12-2 v2).
	 * 이 값을 저장 길이(64)에 맞춰 줄이면 <b>정상 익명키가 전부 AUTH_002 로 거부된다.</b>
	 *
	 * <p>⚠️ 잠정값 — 실제 익명키 규격은 스파이크 6(auth.md 🔶-3)에서 확정된다. 그때까지는
	 * 좁히지 않는다(모르는 채로 좁히면 정상 사용자를 막는다).
	 */
	public static final int MAX_LENGTH = 255;

	/** 마스킹에서 남기는 앞자리 수 (auth.md §4-5 확정: 앞 4자 + "***"). */
	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private AnonymousKeyFormat() {
	}

	/** 형식 검증(U5). 규칙 위반이라고 여기서 거부하지는 않는다 — 판정은 인가 규칙이 한다. */
	public static boolean isValid(String raw) {
		return StringUtils.hasText(raw) && raw.length() <= MAX_LENGTH;
	}

	/**
	 * 로그·진단용 마스킹(U6). 익명키 원문은 로그·예외 메시지·응답 본문 어디에도 남기지 않는다.
	 *
	 * <p>앞 {@value #VISIBLE_PREFIX} 자만 남기고 나머지를 가린다. 앞자리보다 짧은 입력은
	 * 남길 것이 곧 원문이므로 <b>아무것도 남기지 않는다.</b>
	 */
	public static String mask(String raw) {
		if (raw == null || raw.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return raw.substring(0, VISIBLE_PREFIX) + MASK;
	}
}
