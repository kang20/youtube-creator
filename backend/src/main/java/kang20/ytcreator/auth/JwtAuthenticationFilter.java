package kang20.ytcreator.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer} 를 인증 객체로 바꾼다 — 사유만 남기고 판정은 인가 규칙이 한다
 * (auth-design.md §14-2, v1 쟁점 2 원칙 유지).
 *
 * <p>⚠️ <b>어떤 경우에도 요청을 거부하지 않는다.</b> 공개 엔드포인트는 무효 토큰도 무시하고 200 을
 * 줘야 하므로(auth.md §4-2), 여기서 끊으면 그 계약이 깨진다. 공개 경로에서는 attribute 가 버려지고,
 * 인증 필요 경로에서는 {@link TokenAuthenticationEntryPoint} 가 attribute 로 코드를 가른다.
 *
 * <p>검증은 <b>서명·만료뿐이다 — DB 를 보지 않는다</b>(U8). 그 대가로 강제 폐기가 access 수명(30분)만큼
 * 지연되는 것은 수용됐다(§14-6).
 *
 * <p>게이트 부품이 auth 모듈 루트에 있는 이유는 §14-1 — {@code config} 가 조립하는 공개 계약이다.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	/** 거부 사유를 담는 request attribute 키. {@link TokenAuthenticationEntryPoint} 만 읽는다. */
	public static final String DENIAL_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".DENIAL";

	private static final String BEARER_PREFIX = "Bearer ";

	/** 인증이 설정되지 않은 이유. 값 자체가 에러 코드로 번역된다(auth.md §4-2 v4). */
	public enum Denial {
		/** Authorization 헤더가 없거나 공백이다 → AUTH_001 */
		MISSING,
		/** 헤더는 있으나 Bearer 형식이 아니거나 서명·형식이 무효다 → AUTH_002 */
		MALFORMED,
		/** 서명은 유효하나 만료됐다 → AUTH_004 (프론트가 refresh 로 갱신한다) */
		EXPIRED
	}

	private final JwtSupport jwtSupport;

	public JwtAuthenticationFilter(JwtSupport jwtSupport) {
		this.jwtSupport = jwtSupport;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {

		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (!StringUtils.hasText(header)) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MISSING);
		} else if (!header.startsWith(BEARER_PREFIX)) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MALFORMED);
		} else {
			authenticate(request, header.substring(BEARER_PREFIX.length()));
		}
		filterChain.doFilter(request, response);
	}

	private void authenticate(HttpServletRequest request, String accessToken) {
		try {
			SecurityContextHolder.getContext()
				.setAuthentication(new UserAuthentication(jwtSupport.parse(accessToken)));
		} catch (ExpiredJwtException e) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.EXPIRED);
		} catch (JwtException | IllegalArgumentException e) {
			// 서명 위조·형식 불량·빈 토큰 — 재시도해도 소용없는 축이다(AUTH_002)
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MALFORMED);
		}
	}
}
