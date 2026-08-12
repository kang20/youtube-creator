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

/**
 * 미인증 요청을 401 로 끝낸다. 코드는 {@link JwtAuthenticationFilter} 가 남긴 거부 사유로 가른다
 * (auth-design.md §14-2, v1 쟁점 1 패턴 그대로).
 *
 * <p>보안 필터 체인 안에서 끝나 {@code GlobalExceptionHandler} 에 닿지 않으므로
 * {@link ErrorResponse} 를 <b>직접 직렬화</b>한다. 안 그러면 본문 없는 기본 401 이 나가고
 * 프론트의 AUTH_001/AUTH_002/AUTH_004 분기(auth.md §6-2 인터셉터)가 죽는다.
 *
 * <p>만료(AUTH_004)를 무효(AUTH_002)와 가르는 이유: 프론트 행동이 다르다 — 만료는 refresh 후
 * 1회 재시도, 무효는 재시도 무익·재로그인. 섞이면 30분마다 전 사용자가 재로그인한다(auth.md §4-2).
 */
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
