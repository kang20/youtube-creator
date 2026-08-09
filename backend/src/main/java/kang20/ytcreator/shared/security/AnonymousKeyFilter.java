package kang20.ytcreator.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Anonymous-Key 헤더를 인증 객체로 바꾼다. 사유만 남기고 판정은 인가 규칙이 한다.
 *
 * <p>⚠️ <b>어떤 경우에도 요청을 거부하지 않는다.</b> 공개 엔드포인트는 형식이 틀린 익명키도 무시하고
 * 200 을 줘야 하므로, 여기서 끊으면 그 계약이 깨진다(auth-design.md §2-1 쟁점 2).
 *
 * <p>헤더명은 프론트 계약이라 한 글자도 바뀌면 안 된다.
 */
public class AnonymousKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Anonymous-Key";

	/** 거부 사유를 담는 request attribute 키. 진입점만 읽는다. */
	public static final String DENIAL_ATTRIBUTE = AnonymousKeyFilter.class.getName() + ".DENIAL";

	/** 인증이 설정되지 않은 이유. 값 자체가 에러 코드로 번역된다(§5-2). */
	public enum Denial {
		/** 헤더가 없거나 공백이다 → AUTH_001 */
		MISSING,
		/** 헤더는 있으나 형식이 틀렸다 → AUTH_002 */
		MALFORMED
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String key = request.getHeader(HEADER);
		if (!StringUtils.hasText(key)) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MISSING);
		} else if (!AnonymousKeyFormat.isValid(key)) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MALFORMED);
		} else {
			SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthentication(key));
		}
		filterChain.doFilter(request, response);
	}
}
