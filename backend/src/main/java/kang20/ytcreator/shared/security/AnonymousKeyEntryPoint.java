package kang20.ytcreator.shared.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kang20.ytcreator.shared.dto.ErrorResponse;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * 미인증 요청을 401 로 끝낸다. 코드는 {@link AnonymousKeyFilter} 가 남긴 거부 사유로 가른다.
 *
 * <p>보안 필터 체인 안에서 끝나 {@code GlobalExceptionHandler} 에 닿지 않으므로
 * {@link ErrorResponse} 를 <b>직접 직렬화</b>한다. 안 그러면 본문 없는 기본 401 이 나가고
 * 프론트의 AUTH_001/AUTH_002 분기가 죽는다(auth-design.md §2-1 쟁점 1).
 *
 * <p>⚠️ 응답·로그에 익명키 원문을 넣지 마라 — 필요하면 {@link AnonymousKeyFormat#mask(String)}.
 */
public class AnonymousKeyEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public AnonymousKeyEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {

		ErrorCode errorCode = AnonymousKeyFilter.Denial.MALFORMED
			.equals(request.getAttribute(AnonymousKeyFilter.DENIAL_ATTRIBUTE))
			? ErrorCode.AUTH_002    // 헤더는 왔는데 형식이 틀렸다
			: ErrorCode.AUTH_001;   // 헤더가 없다(MISSING) — attribute 자체가 없는 경우도 여기로

		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
	}
}
