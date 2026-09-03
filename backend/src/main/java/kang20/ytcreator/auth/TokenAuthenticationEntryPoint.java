package kang20.ytcreator.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kang20.ytcreator.shared.dto.ErrorResponse;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TokenAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public TokenAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		ErrorCode errorCode = errorCodeOf(request.getAttribute(JwtAuthenticationFilter.DENIAL_ATTRIBUTE));

		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
	}

	private ErrorCode errorCodeOf(Object denial) {
		if (JwtAuthenticationFilter.Denial.EXPIRED.equals(denial)) {
			return ErrorCode.AUTH_004;    // 만료 — 프론트가 refresh 로 갱신 후 1회 재시도
		}
		if (JwtAuthenticationFilter.Denial.MALFORMED.equals(denial)) {
			return ErrorCode.AUTH_002;    // 무효 — 재시도 무익, 부트스트랩 재로그인
		}
		return ErrorCode.AUTH_001;        // 없음(MISSING) — attribute 자체가 없는 경우도 여기로
	}
}
