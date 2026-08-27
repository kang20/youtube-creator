package kang20.ytcreator.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kang20.ytcreator.shared.dto.ErrorResponse;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증은 됐으나 권한이 모자란 요청을 403 {@code AUTH_003} 으로 끝낸다
 * ({@link TokenAuthenticationEntryPoint} 의 403 짝).
 *
 * <p>이것이 없으면 스프링 기본 403 이 <b>본문 없이</b> 나가서, 모든 에러가
 * {@code {code, message}} 라는 계약(error-handling.md)이 권한 축에서만 깨진다.
 * 보안 필터 체인 안에서 끝나 {@code GlobalExceptionHandler} 에 닿지 않으므로 직접 직렬화한다.
 *
 * <p>⚠️ <b>401 로 바꾸지 마라.</b> 프론트 행동이 정반대다 — 401 이면 인터셉터가 refresh·재로그인을
 * 반복하는데, 토큰은 멀쩡하고 권한만 없는 상태라 영영 풀리지 않는다(무한 루프).
 *
 * <p>어떤 권한이 필요했는지는 <b>응답에 싣지 않는다</b> — 운영자 경로의 존재를 알려 줄 이유가 없다.
 */
@Component
public class RoleAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public RoleAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {

		response.setStatus(ErrorCode.AUTH_003.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(ErrorCode.AUTH_003));
	}
}
