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
 * 인증이 필요한 경로에서 미인증 요청을 401 로 끝낸다.
 *
 * <p>이 응답은 보안 필터 체인 안에서 끝나므로 {@code GlobalExceptionHandler} 에 도달하지 않는다.
 * 그래서 공통 에러 본문({@link ErrorResponse})을 <b>여기서 직접 직렬화</b>한다 —
 * 그러지 않으면 본문 없는 스프링 기본 401 이 나가고 프론트의 AUTH_001/AUTH_002 분기가 전부 죽는다
 * (docs/domain/auth-design.md §2-1 쟁점 1).
 *
 * <p>코드는 {@link AnonymousKeyFilter} 가 남긴 거부 사유로 가른다.
 * <b>익명키 원문은 응답에도 로그에도 넣지 않는다</b>(U6) — 필요하면
 * {@link AnonymousKeyFormat#mask(String)} 을 거친다.
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
