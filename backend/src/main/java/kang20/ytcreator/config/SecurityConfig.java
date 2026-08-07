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
 * <p><b>기본은 인증 필요(default-deny)</b>다 — 공개로 열 경로만 아래에 열거한다.
 * 새 엔드포인트는 아무것도 하지 않으면 401 이 된다(docs/domain/auth-design.md §7).
 *
 * <p>미인증 401 은 보안 필터 체인 안에서 끝나 GlobalExceptionHandler 에 닿지 않으므로
 * {@link AnonymousKeyEntryPoint} 가 공통 에러 본문을 직접 쓴다(§2-1 쟁점 1).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/**
	 * 인증 없이 열어 두는 경로.
	 *
	 * <p>값의 정본은 docs/domain/auth.md §4-2 다. actuator 는 <b>전체</b>를 연다 —
	 * 헬스체크만 열면 Prometheus 스크레이프(/actuator/prometheus)가 401 이 되어 모니터링이 죽는다.
	 * 외부 노출은 Caddy 가 /actuator* 를 403 으로 막고, 수집은 루프백(SSH 역터널) 경로로만 들어온다.
	 *
	 * <p>솔루션·부트스트랩 엔드포인트는 아직 없으므로 지금 열거되는 것은 actuator 뿐이고,
	 * 나머지는 해당 도메인이 생길 때 추가된다(auth-design.md §5-2).
	 */
	private static final String[] PUBLIC_PATHS = {
		"/actuator/**"
	};

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
		return http
			// CORS preflight(OPTIONS)는 인가 앞단의 CorsFilter 가 처리한다 — 게이트에 도달하지 않는다.
			// 브라우저는 preflight 에 X-Anonymous-Key 를 싣지 않으므로 원리상 인증될 수 없고,
			// 이건 경로 축이 아니라 메서드 축이라 공개 경로 열거로는 풀리지 않는다(auth-design.md §5-2).
			// 허용 오리진 정책은 WebConfig 의 것을 그대로 쓴다 — 여기서 CORS 정책을 새로 정하지 않는다.
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
