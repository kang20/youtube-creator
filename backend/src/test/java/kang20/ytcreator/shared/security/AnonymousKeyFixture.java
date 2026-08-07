package kang20.ytcreator.shared.security;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 익명키 테스트 데이터 표준화 (docs/rule/testing.md "Fixture 클래스로 테스트 데이터 표준화").
 *
 * <p>익명키는 <b>auth 모듈이 아니라 shared/security 의 개념</b>이므로(auth-design.md §2-1 쟁점 3)
 * 픽스처도 여기 둔다. auth 모듈 테스트와 게이트 테스트가 같은 값을 쓴다.
 *
 * <p>경계값은 {@link AnonymousKeyFormat#MAX_LENGTH} 에서 직접 계산한다 —
 * 스파이크 6 으로 형식 규격이 확정되어 상수가 바뀌면(auth.md §9-1 🔶-3) 이 픽스처가 함께 따라온다.
 */
public final class AnonymousKeyFixture {

	/** 형식이 정상인 익명키. 한 테스트 안에서만 쓰고, 데이터가 남는 테스트는 {@link #unique(String)}. */
	public static final String VALID = "toss-anon-hash-0123456789abcdef";

	/** 헤더는 있으나 공백뿐 — auth.md §4-4 "공백/빈 값은 헤더 없음과 동일 취급(AUTH_001)". */
	public static final String BLANK = "   ";

	private static final AtomicInteger SEQ = new AtomicInteger();

	private AnonymousKeyFixture() {
	}

	/** 테스트끼리 사용자 행이 겹치지 않도록 매번 다른 익명키를 만든다. */
	public static String unique(String label) {
		return "toss-anon-" + label + "-" + SEQ.incrementAndGet();
	}

	/** 길이 상한 경계 — 딱 MAX_LENGTH 자. 통과해야 한다(auth-design.md §12-2). */
	public static String atMaxLength() {
		return "a".repeat(AnonymousKeyFormat.MAX_LENGTH);
	}

	/** 길이 상한 초과 — MAX_LENGTH + 1 자. 형식 위반이므로 AUTH_002 다(auth.md §4-2). */
	public static String tooLong() {
		return "a".repeat(AnonymousKeyFormat.MAX_LENGTH + 1);
	}
}
