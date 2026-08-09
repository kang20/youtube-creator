package kang20.ytcreator.config;

import kang20.ytcreator.shared.security.AnonymousKeyEntryPoint;
import kang20.ytcreator.shared.security.AnonymousKeyFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * stateless 익명키 인증. 세션·CSRF 를 쓰지 않는다.
 *
 * <p><b>기본은 인증 필요(default-deny)</b> — 새 엔드포인트는 아무것도 하지 않으면 401 이 된다.
 * 공개로 열 경로만 {@link #PUBLIC_PATHS} 에 열거한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * 인증 없이 열어 두는 경로. 값의 정본은 auth.md §4-2.
	 *
	 * <p>actuator 는 <b>전체</b>를 연다 — 헬스체크만 열면 Prometheus 스크레이프가 401 이 되어
	 * 모니터링이 조용히 죽는다. 외부 노출은 Caddy 가 막는다.
	 */
	private static final String[] PUBLIC_PATHS = {
		"/actuator/**"
	};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		return http
			// ⚠️ 지우지 마라 — preflight 는 X-Anonymous-Key 를 싣지 않아 원리상 인증될 수 없다.
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
				.authenticationEntryPoint(new AnonymousKeyEntryPoint(objectMapper)))
			.addFilterBefore(new AnonymousKeyFilter(), UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
