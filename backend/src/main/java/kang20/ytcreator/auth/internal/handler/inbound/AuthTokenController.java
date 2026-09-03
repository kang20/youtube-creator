package kang20.ytcreator.auth.internal.handler.inbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.TokenPair;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthTokenController {

	private final AuthPort authPort;

	public AuthTokenController(AuthPort authPort) {
		this.authPort = authPort;
	}

	@PostMapping("/api/v1/auth/refresh")
	public TokenPair refresh(@Valid @RequestBody RefreshRequest request) {
		return authPort.refresh(request.refreshToken());
	}

	record RefreshRequest(@NotBlank String refreshToken) {
	}
}
