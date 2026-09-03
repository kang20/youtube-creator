package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityGateTest.ProbeEndpoint.class)
class SecurityGateTest {

	private static final String PROTECTED_PATH = "/api/v1/gate-probe";

	private static final String ADMIN_PATH = "/api/v1/admin/gate-probe";

	private static final String USER_ONLY_PATH = "/api/v1/hierarchy-probe";

	private static final String PUBLIC_HEALTH = "/actuator/health";
	private static final String PUBLIC_PROMETHEUS = "/actuator/prometheus";

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	private static final UserId USER = new UserId(424_242L);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtSupport jwtSupport;

	@Autowired
	private UserRepository userRepository;

	@Value("${ytcreator.auth.jwt.secret}")
	private String secret;

	@TestConfiguration
	static class ProbeEndpoint {

		@Bean
		RouterFunction<ServerResponse> gateProbeRoute() {
			// U8 — 게이트가 확정한 요청 주체(principal)를 그대로 되돌린다
			return RouterFunctions.route()
				.GET(PROTECTED_PATH, request -> ServerResponse.ok()
					.body(String.valueOf(
						SecurityContextHolder.getContext().getAuthentication().getPrincipal())))
				.GET(ADMIN_PATH, request -> ServerResponse.ok().body("admin"))
				.GET(USER_ONLY_PATH, request -> ServerResponse.ok().body("user-only"))
				.build();
		}

		@Bean
		@Order(0)
		SecurityFilterChain userOnlyProbeChain(HttpSecurity http,
				JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
			return http
				.securityMatcher(USER_ONLY_PATH)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().hasRole("USER"))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
		}
	}

	private String bearer() {
		return "Bearer " + jwtSupport.issue(USER);
	}

	private String expiredBearer() {
		Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC);
		return "Bearer " + new JwtSupport(secret, past).issue(USER);
	}

	private String forgedBearer() {
		Clock now = Clock.systemUTC();
		return "Bearer " + new JwtSupport("forged-hs256-secret-0123456789abcdef!!!", now).issue(USER);
	}

	@Test
	@DisplayName("공개 경로는 Authorization 헤더 없이 200 이다")
	void 공개경로_헤더_없음() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("actuator 는 헬스체크만이 아니라 전체가 공개다 — 스크레이프 경로도 200")
	void 공개경로는_actuator_전체다() throws Exception {
		mockMvc.perform(get(PUBLIC_PROMETHEUS))
			.andExpect(status().isOk());
		mockMvc.perform(get("/actuator"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개 경로는 위조 토큰이 실려 있어도 무시하고 200 이다")
	void 공개경로_위조_토큰은_무시된다() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, forgedBearer()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개 경로는 만료 토큰이 실려 있어도 200 이다")
	void 공개경로_만료_토큰도_무시된다() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, expiredBearer()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개 경로는 정상 토큰이 있어도 200 이다")
	void 공개경로_정상_토큰() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("보호 경로는 Authorization 헤더가 없으면 401 AUTH_001 이다")
	void 보호경로_헤더_없음() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_001.getMessage()));
	}

	@Test
	@DisplayName("보호 경로에 공백 Authorization 을 보내면 401 AUTH_001 이다")
	void 보호경로_공백_헤더() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "   "))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	@Test
	@DisplayName("보호 경로에서 X-Anonymous-Key 는 자격 증명이 아니다 — 401 AUTH_001")
	void 보호경로_익명키는_무시된다() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header("X-Anonymous-Key", "toss-anon-hash-0123456789abcdef"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	@Test
	@DisplayName("보호 경로에 위조 서명 토큰을 보내면 401 AUTH_002 다")
	void 보호경로_위조_서명() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, forgedBearer()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_002.getMessage()));
	}

	@Test
	@DisplayName("보호 경로에 JWT 형식이 아닌 Bearer 값을 보내면 401 AUTH_002 다")
	void 보호경로_형식_불량() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
	}

	@Test
	@DisplayName("Bearer 가 아닌 인증 스킴은 401 AUTH_002 다")
	void 보호경로_다른_스킴() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwdw=="))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
	}

	@Test
	@DisplayName("보호 경로에 만료 토큰을 보내면 AUTH_002 가 아니라 401 AUTH_004 다")
	void 보호경로_만료() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, expiredBearer()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_004.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_004.getMessage()));
	}

	@Test
	@DisplayName("정상 토큰은 200 이고 요청 주체는 토큰의 userId 다 — DB 존재 확인은 하지 않는다")
	void 보호경로_정상_토큰() throws Exception {
		assertThat(userRepository.findById(USER))
			.as("전제 — 이 userId 의 행은 DB 에 없다. 그래도 통과해야 U8 이다")
			.isEmpty();

		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			// U8 — 게이트가 확정한 요청 주체 = 토큰 sub 의 UserId
			.andExpect(content().string(USER.toString()));
	}

	@Test
	@DisplayName("열거되지 않은 경로는 기본이 인증 필요다 — 공개는 명시적 열거뿐이다")
	void 기본값은_인증_필요다() throws Exception {
		mockMvc.perform(get("/api/v1/anything-else"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	@Test
	@DisplayName("일반 사용자 토큰으로 운영자 경로를 부르면 403 이다")
	void 어드민경로_일반_사용자는_403() throws Exception {
		mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_003.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_003.getMessage()));
	}

	@Test
	@DisplayName("운영자 토큰은 운영자 경로를 통과한다")
	void 어드민경로_운영자는_200() throws Exception {
		mockMvc.perform(get(ADMIN_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtSupport.issue(USER, Role.ADMIN)))
			.andExpect(status().isOk())
			.andExpect(content().string("admin"));
	}

	@Test
	@DisplayName("ADMIN 은 USER 로 막은 경로도 통과한다 — 권한 계층")
	void 권한_계층_ADMIN_은_USER_를_포함한다() throws Exception {
		mockMvc.perform(get(USER_ONLY_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtSupport.issue(USER, Role.ADMIN)))
			.andExpect(status().isOk())
			.andExpect(content().string("user-only"));
	}

	@Test
	@DisplayName("USER 는 USER 경로만 통과한다 — 계층은 역방향으로 열리지 않는다")
	void 권한_계층은_한_방향이다() throws Exception {
		mockMvc.perform(get(USER_ONLY_PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());
		mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("운영자 경로도 토큰이 없으면 401 AUTH_001 이다")
	void 어드민경로_헤더_없음() throws Exception {
		mockMvc.perform(get(ADMIN_PATH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	@Test
	@DisplayName("CORS preflight 는 토큰이 없어도 401 이 아니다")
	void preflight_는_게이트를_거치지_않는다() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
	}

	@Test
	@DisplayName("preflight 를 열어도 실제 크로스 오리진 요청은 토큰 없이는 401 이다")
	void preflight_가_인증_구멍을_내지_않는다() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	@Test
	@DisplayName("허용되지 않은 오리진의 preflight 는 여전히 막힌다 — CORS 정책은 그대로다")
	void 허용되지_않은_오리진은_막힌다() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
				.header("Origin", "http://evil.example.com")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isForbidden());
	}
}
