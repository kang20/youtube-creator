package kang20.ytcreator.bootstrap.dto;

import java.time.LocalDateTime;

public record BootstrapResponse(boolean newUser, LocalDateTime registeredAt, AuthTokens auth) {

	public record AuthTokens(String accessToken, String refreshToken) {
	}
}
