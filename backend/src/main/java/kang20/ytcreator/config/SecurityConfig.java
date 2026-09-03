package kang20.ytcreator.config;

import kang20.ytcreator.auth.JwtAuthenticationFilter;
import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.RoleAccessDeniedHandler;
import kang20.ytcreator.auth.TokenAuthenticationEntryPoint;
import kang20.ytcreator.auth.UserAuthentication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
		"/actuator/**",
		// 부트스트랩 — 최초 사용자는 토큰이 없다(등록을 요구하는 게이트 뒤에 두면 영원히 통과 못 한다).
		// 익명키 필수·U5 검증은 BootstrapController 가 직접 한다(auth-design.md §14-2)
		"/api/v1/bootstrap",
		// 갱신 — access 가 만료된 상태에서 부르는 API 라 게이트 밖이다. 본문의 refresh 가 자격 증명(auth.md §5-5)
		"/api/v1/auth/refresh",
		// 토스 웹훅 — 토스는 우리 토큰을 보내지 않는다. 빠지면 구독 상태 변화를 아는 유일한 경로가 죽는다.
		// permitAll 이지만 subscription 모듈이 Basic Auth 로 다시 막는다(두 번째 체인을 만들지 않는다).
		// Authorization 헤더를 Bearer 가 아닌 값으로 쓰는 유일한 경로다 — JwtAuthenticationFilter 가
		//    Bearer 아닌 헤더를 거부하지 않고 통과시키기 때문에 성립한다.
		// 여기 적는 것은 경로 문자열뿐이다 — config → subscription 의존을 만들지 않는다
		"/api/v1/webhooks/toss/**"
		// payment 롤백(2026-08-14)으로 아래 경로가 빠졌다. 재구현 시 반드시 되살린다:
		//   "/api/v1/payments/products" — 결제 유도 전에 보이는 목록이라 토큰 없이 연다(auth.md §4-2)
	};

	private static final String[] ADMIN_PATHS = {
		"/api/v1/admin/**"
	};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
			TokenAuthenticationEntryPoint tokenAuthenticationEntryPoint,
			RoleAccessDeniedHandler roleAccessDeniedHandler) throws Exception {
		return http
			// 지우지 마라 — preflight 는 Authorization 을 싣지 않아 원리상 인증될 수 없다.
			// 메서드 축의 예외라 공개 경로 열거로는 풀리지 않는다. 오리진 정책은 WebConfig 것을 쓴다.
			.cors(Customizer.withDefaults())
			.csrf(csrf -> csrf.disable())
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(PUBLIC_PATHS).permitAll()
				// 운영자 전용 — 순서가 중요하다. anyRequest() 뒤에 두면 규칙 자체가 평가되지 않는다.
				// 경로로 가르는 이유는 공개 경로와 같다: 어노테이션 누락 = 조용한 노출을 막는다
				.requestMatchers(ADMIN_PATHS).hasRole(Role.ADMIN.name())
				.anyRequest().authenticated())
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint(tokenAuthenticationEntryPoint)
				// 권한 부족(403)도 {code,message} 계약을 지킨다 — 없으면 본문 없는 기본 403 이 나간다
				.accessDeniedHandler(roleAccessDeniedHandler))
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	@Bean
	public RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.withDefaultRolePrefix()
			.role(Role.ADMIN.name()).implies(Role.USER.name())
			.build();
	}

	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
			JwtAuthenticationFilter jwtAuthenticationFilter) {
		FilterRegistrationBean<JwtAuthenticationFilter> registration =
			new FilterRegistrationBean<>(jwtAuthenticationFilter);
		registration.setEnabled(false);
		return registration;
	}
}
