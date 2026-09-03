package kang20.ytcreator.auth.dto;

import java.time.LocalDateTime;
import kang20.ytcreator.auth.UserId;

public record LoginResult(boolean newUser, LocalDateTime registeredAt, UserId userId,
		String accessToken, String refreshToken) {
}
