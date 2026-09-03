package kang20.ytcreator.auth;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kang20.ytcreator.auth.internal.UserPrincipal;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	public static final String DENIAL_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".DENIAL";

	private static final String BEARER_PREFIX = "Bearer ";

	public enum Denial {
		MISSING,
		MALFORMED,
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
			UserPrincipal principal = jwtSupport.parse(accessToken);
			SecurityContextHolder.getContext()
				.setAuthentication(new UserAuthentication(principal.userId(), principal.role()));
		} catch (ExpiredJwtException e) {
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.EXPIRED);
		} catch (JwtException | IllegalArgumentException e) {
			// 서명 위조·형식 불량·빈 토큰 — 재시도해도 소용없는 축이다(AUTH_002)
			request.setAttribute(DENIAL_ATTRIBUTE, Denial.MALFORMED);
		}
	}
}
