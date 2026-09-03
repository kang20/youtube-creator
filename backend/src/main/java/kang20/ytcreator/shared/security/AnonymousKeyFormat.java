package kang20.ytcreator.shared.security;

import org.springframework.util.StringUtils;

public final class AnonymousKeyFormat {

	public static final int MAX_LENGTH = 255;

	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private AnonymousKeyFormat() {
	}

	public static boolean isValid(String raw) {
		return StringUtils.hasText(raw) && raw.length() <= MAX_LENGTH;
	}

	public static String mask(String raw) {
		if (raw == null || raw.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return raw.substring(0, VISIBLE_PREFIX) + MASK;
	}
}
