package kang20.ytcreator.auth.internal.handler.inbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.TokenPair;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/refresh} — auth 의 유일한 HTTP 엔드포인트(auth.md §5-3 v4 번복).
 *
 * <p><b>인증 게이트 밖이다</b>(SecurityConfig PUBLIC_PATHS) — 자격 증명(access)이 만료된 상태에서
 * 부르는 API 라 게이트 뒤에 둘 수 없다. 본문의 refresh 가 곧 자격 증명이다(auth.md §5-5).
 *
 * <p>구현체를 직접 참조하지 않는다 — {@link AuthPort} 로만 부른다(R5). 실패는 서비스가
 * {@code AUTH_005} 로 던지고 {@code GlobalExceptionHandler} 가 401 본문으로 바꾼다.
 */
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

	/**
	 * 갱신 요청(auth.md §5-5). 빈 값은 검증(COMMON_001)이 거르고, 값이 있는데 무효면
	 * 서비스가 AUTH_005 로 거른다 — 두 축을 섞지 않는다.
	 */
	record RefreshRequest(@NotBlank String refreshToken) {
	}
}
