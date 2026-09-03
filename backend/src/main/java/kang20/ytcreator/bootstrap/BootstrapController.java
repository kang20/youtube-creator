package kang20.ytcreator.bootstrap;

import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.bootstrap.dto.BootstrapResponse;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.shared.security.AnonymousKeyFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BootstrapController {

	public static final String ANONYMOUS_KEY_HEADER = "X-Anonymous-Key";

	private final AuthPort authPort;

	public BootstrapController(AuthPort authPort) {
		this.authPort = authPort;
	}

	@PostMapping("/api/v1/bootstrap")
	public BootstrapResponse bootstrap(
			@RequestHeader(name = ANONYMOUS_KEY_HEADER, required = false) String anonymousKey) {
		validate(anonymousKey);

		LoginResult login = authPort.login(anonymousKey);

		return new BootstrapResponse(login.newUser(), login.registeredAt(),
			new BootstrapResponse.AuthTokens(login.accessToken(), login.refreshToken()));
	}

	private void validate(String anonymousKey) {
		if (!StringUtils.hasText(anonymousKey)) {
			throw new BusinessException(ErrorCode.AUTH_001);
		}
		if (!AnonymousKeyFormat.isValid(anonymousKey)) {
			throw new BusinessException(ErrorCode.AUTH_002);
		}
	}
}
