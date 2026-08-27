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
		// 토스 웹훅 — 토스는 우리 토큰을 보내지 않는다. 빠지면 구독 상태 변화를 아는 유일한 경로가 죽는다.
		// permitAll 이지만 subscription 모듈이 Basic Auth 로 다시 막는다(두 번째 체인을 만들지 않는다).
		// ⚠️ Authorization 헤더를 Bearer 가 아닌 값으로 쓰는 유일한 경로다 — JwtAuthenticationFilter 가
		//    Bearer 아닌 헤더를 거부하지 않고 통과시키기 때문에 성립한다.
		// ⚠️ 여기 적는 것은 경로 문자열뿐이다 — config → subscription 의존을 만들지 않는다
		"/api/v1/webhooks/toss/**"
		// ⚠️ payment 롤백(2026-08-14)으로 아래 경로가 빠졌다. 재구현 시 반드시 되살린다:
		//   "/api/v1/payments/products" — 결제 유도 전에 보이는 목록이라 토큰 없이 연다(auth.md §4-2)
	};

	/**
	 * 운영자({@link Role#ADMIN}) 전용 경로. <b>접두사 하나로 고정</b>한다 — 운영 기능이 일반 경로에
	 * 흩어지면 이 목록이 곧 인가 규칙이라는 성질이 깨진다. 토큰은 유효하나 권한이 없으면 403 이다.
	 */
	private static final String[] ADMIN_PATHS = {
		"/api/v1/admin/**"
	};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
			TokenAuthenticationEntryPoint tokenAuthenticationEntryPoint,
			RoleAccessDeniedHandler roleAccessDeniedHandler) throws Exception {
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

	/**
	 * 권한 계층 — <b>ADMIN 은 USER 가 할 수 있는 것을 전부 할 수 있다.</b>
	 *
	 * <p>{@link UserAuthentication} 이 authority 를 <b>하나만</b> 담는 것과 짝이다: 토큰에는
	 * 자기 권한 하나만 싣고(그래야 "일반 사용자만" 같은 규칙을 표현할 수 있다), 포함 관계는
	 * 여기 한 곳에서 선언한다. 두 권한을 authority 에 같이 넣어 흉내 내면 그 구분이 사라진다.
	 *
	 * <p>계층이 없으면 {@code hasRole("USER")} 로 막은 일반 기능에서 <b>운영자가 403</b> 을 맞는다 —
	 * 실무에서 가장 흔한 함정이라 규칙을 쓰기 전에 미리 깐다.
	 *
	 * <p>⚠️ 이 빈이 사라지면 컴파일도 테스트도 조용히 통과하고 운영에서만 403 이 난다.
	 * {@code SecurityGateTest} 의 계층 테스트가 그 유일한 방지선이다.
	 */
	@Bean
	public RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.withDefaultRolePrefix()
			.role(Role.ADMIN.name()).implies(Role.USER.name())
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
