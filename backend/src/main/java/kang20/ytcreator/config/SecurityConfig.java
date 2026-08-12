package kang20.ytcreator.config;

import kang20.ytcreator.auth.JwtAuthenticationFilter;
import kang20.ytcreator.auth.TokenAuthenticationEntryPoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * stateless JWT 인증(auth.md v4). 세션·CSRF 를 쓰지 않는다.
 *
 * <p><b>기본은 인증 필요(default-deny)</b> — 새 엔드포인트는 아무것도 하지 않으면 401 이 된다.
 * youngZZ 의 permitAll+리졸버 인가를 따르지 않는 이유: 어노테이션 누락 = 조용한 공개 함정
 * (auth-design.md §14-2). 공개로 열 경로만 {@link #PUBLIC_PATHS} 에 열거한다.
 *
 * <p>게이트 부품(필터·진입점)은 auth 모듈의 공개 계약이고 여기는 <b>조립만</b> 한다(§14-1).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * 인증 없이 열어 두는 경로. 값의 정본은 auth.md §4-2 (v4).
	 *
	 * <p>actuator 는 <b>전체</b>를 연다 — 헬스체크만 열면 Prometheus 스크레이프가 401 이 되어
	 * 모니터링이 조용히 죽는다. 외부 노출은 Caddy 가 막는다.
	 */
	private static final String[] PUBLIC_PATHS = {
		"/actuator/**",
		// 부트스트랩 — 최초 사용자는 토큰이 없다(등록을 요구하는 게이트 뒤에 두면 영원히 통과 못 한다).
		// 익명키 필수·U5 검증은 BootstrapController 가 직접 한다(auth-design.md §14-2)
		"/api/v1/bootstrap",
		// 갱신 — access 가 만료된 상태에서 부르는 API 라 게이트 밖이다. 본문의 refresh 가 자격 증명(auth.md §5-5)
		"/api/v1/auth/refresh",
		// 상품 조회 — 결제 유도 전에 보이는 목록이라 토큰 없이 연다(payment-design.md §7 · auth.md §4-2)
		"/api/v1/payments/products",
		// 토스 웹훅 — 토스는 우리 토큰을 보내지 않는다(payment.md §10-8ⓐ). 빠지면 U9 가 통째로
		// 죽는다. permitAll 이지만 payment 모듈이 Basic Auth 로 다시 막는다(payment-design.md §2-1 쟁점 3)
		"/api/v1/webhooks/toss/**"
	};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
			TokenAuthenticationEntryPoint tokenAuthenticationEntryPoint) throws Exception {
		return http
			// ⚠️ 지우지 마라 — preflight 는 Authorization 을 싣지 않아 원리상 인증될 수 없다.
			// 메서드 축의 예외라 공개 경로 열거로는 풀리지 않는다. 오리진 정책은 WebConfig 것을 쓴다.
			.cors(Customizer.withDefaults())
			.csrf(csrf -> csrf.disable())
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(PUBLIC_PATHS).permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint(tokenAuthenticationEntryPoint))
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	/**
	 * ⚠️ 서블릿 컨테이너 자동 등록 차단 — {@code @Component} 인 {@code Filter} 빈은 Boot 가 보안 체인
	 * <b>밖에서</b> 한 번 더 등록한다. 보안 체인 안({@code addFilterBefore})에서만 돌게 여기서 끈다.
	 */
	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
			JwtAuthenticationFilter jwtAuthenticationFilter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration =
			new FilterRegistrationBean<>(jwtAuthenticationFilter);
		registration.setEnabled(false);
		return registration;
	}
}
