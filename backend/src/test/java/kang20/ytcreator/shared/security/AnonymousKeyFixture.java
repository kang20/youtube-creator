package kang20.ytcreator.shared.security;

import java.util.concurrent.atomic.AtomicInteger;

public final class AnonymousKeyFixture {

	public static final String VALID = "toss-anon-hash-0123456789abcdef";

	public static final String BLANK = "   ";

	private static final AtomicInteger SEQ = new AtomicInteger();

	private AnonymousKeyFixture() {
	}

	public static String unique(String label) {
		return "toss-anon-" + label + "-" + SEQ.incrementAndGet();
	}

	public static String atMaxLength() {
		return "a".repeat(AnonymousKeyFormat.MAX_LENGTH);
	}

	public static String tooLong() {
		return "a".repeat(AnonymousKeyFormat.MAX_LENGTH + 1);
	}
}
