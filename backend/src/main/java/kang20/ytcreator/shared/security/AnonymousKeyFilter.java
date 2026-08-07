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
 * X-Anonymous-Key 헤더를 인증 객체로 바꾼다.
 *
 * <p><b>이 필터는 어떤 경우에도 요청을 거부하지 않는다.</b> 헤더가 없어도, 형식이 틀려도 통과시키고
 * 거부 사유만 request attribute 로 남긴다. 공개 엔드포인트는 형식이 틀린 익명키를 무시하고 200 을
 * 줘야 하므로 필터가 끊으면 계약이 깨진다(docs/domain/auth-design.md §2-1 쟁점 2).
 *
 * <pre>
 *   헤더 없음/공백 → 인증 미설정 + attribute MISSING   → 판정은 인가 규칙이
 *   형식 위반      → 인증 미설정 + attribute MALFORMED → 판정은 인가 규칙이
 *   정상           → AnonymousAuthentication 설정
 * </pre>
 *
 * <p>공개 경로면 attribute 는 쓰이지 않고 버려지고, 인증 필요 경로면
 * {@link AnonymousKeyEntryPoint} 가 읽어 AUTH_001 / AUTH_002 를 가른다.
 *
 * <p>헤더명은 docs/server/api-spec.md 와 한 글자도 달라선 안 된다(프론트 계약).
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
