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
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * U3·U8(토큰 게이트) 통합 — auth.md §4-2 <b>(v4)</b> "엔드포인트 분류 × 자격 증명 상태" 표를
 * 그대로 검증한다. 자격 증명은 익명키가 아니라 {@code Authorization: Bearer {access}} 다.
 *
 * <p>게이트를 관측할 <b>테스트 전용 보호 경로</b>를 하나 띄운다(§14-5 개정 — v2 방식 승계).
 * 이 경로는 인증된 principal 을 본문으로 되돌려 U8("userId 를 요청 주체로 삼는다")을 관측한다.
 *
 * <p>⚠️ {@code @WebMvcTest} 슬라이스가 아니라 {@code @SpringBootTest} 인 이유: CORS preflight 는
 * {@code WebConfig} 의 오리진 정책이 함께 올라와야 재현되고(round-2-dev.md 테스터 노트),
 * U8 의 "DB 조회 없음"은 실제 리포지토리가 있는 컨텍스트에서만 의미가 있다.
 *
 * <p>만료·위조 토큰은 <b>운영과 같은 서명 코드</b>({@link JwtSupport})로 만든다 — 만료는 과거로
 * 고정한 {@code Clock} 의 발급기, 위조는 다른 키의 발급기. 토큰 문자열을 손으로 조립하면
 * 테스트가 자체 JWT 규격을 갖게 된다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(SecurityGateTest.ProbeEndpoint.class)
class SecurityGateTest {

	/** 인증이 필요한 경로. default-deny 이므로 아무 설정을 하지 않으면 401 이다(auth-design.md §7). */
	private static final String PROTECTED_PATH = "/api/v1/gate-probe";

	/** auth.md §4-2 (v2) — 공개 대상은 헬스체크 하나가 아니라 운영 엔드포인트 전체다. */
	private static final String PUBLIC_HEALTH = "/actuator/health";
	private static final String PUBLIC_PROMETHEUS = "/actuator/prometheus";

	/** WebConfig 기본 허용 오리진(로컬 개발 서버). CORS 정책은 v4 에서도 손대지 않았다. */
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	/** 게이트는 DB 를 보지 않으므로(U8) 사용자 행이 없어도 서명만 맞으면 통과해야 한다. */
	private static final UserId USER = new UserId(424_242L);

	@Autowired
	private MockMvc mockMvc;

	/** 운영과 같은 발급기 — 정상 토큰은 이걸로 만든다. */
	@Autowired
	private JwtSupport jwtSupport;

	@Autowired
	private UserRepository userRepository;

	/** 만료·위조 발급기를 만들기 위한 서명 키(application-test.yml — 테스트 전용 값). */
	@Value("${ytcreator.auth.jwt.secret}")
	private String secret;

	/** 게이트를 관측하기 위한 테스트 전용 보호 경로. 함수형 엔드포인트라 컴포넌트 스캔에 잡히지 않는다. */
	@TestConfiguration
	static class ProbeEndpoint {

		@Bean
		RouterFunction<ServerResponse> gateProbeRoute() {
			// U8 — 게이트가 확정한 요청 주체(principal)를 그대로 되돌린다
			return RouterFunctions.route()
				.GET(PROTECTED_PATH, request -> ServerResponse.ok()
					.body(String.valueOf(
						SecurityContextHolder.getContext().getAuthentication().getPrincipal())))
				.build();
		}
	}

	private String bearer() {
		return "Bearer " + jwtSupport.issue(USER);
	}

	/** 서명은 우리 키인데 이미 만료된 access — 31분 전 시계로 발급한다(수명 30분, U7). */
	private String expiredBearer() {
		Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(31)), ZoneOffset.UTC);
		return "Bearer " + new JwtSupport(secret, past).issue(USER);
	}

	/** 다른 서명 키로 만든 위조 access — 형식은 완전한 JWT 다. */
	private String forgedBearer() {
		Clock now = Clock.systemUTC();
		return "Bearer " + new JwtSupport("forged-hs256-secret-0123456789abcdef!!!", now).issue(USER);
	}

	// ── 공개 경로 (auth.md §4-2 "공개" 행 — 없음/무효/만료/정상 전부 200) ──────────

	/** §4-2 공개 · 자격 증명 없음 → 200 */
	@Test
	@DisplayName("공개 경로는 Authorization 헤더 없이 200 이다")
	void 공개경로_헤더_없음() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH))
			.andExpect(status().isOk());
	}

	/**
	 * auth.md v2 §4-2 ⑩ · blockers B1 — 공개 대상은 {@code /actuator/**} <b>전체</b>다.
	 * 헬스체크만 열면 Prometheus 스크레이프가 401 이 되어 Grafana 알림이 조용히 멎는다.
	 */
	@Test
	@DisplayName("actuator 는 헬스체크만이 아니라 전체가 공개다 — 스크레이프 경로도 200")
	void 공개경로는_actuator_전체다() throws Exception {
		mockMvc.perform(get(PUBLIC_PROMETHEUS))
			.andExpect(status().isOk());
		mockMvc.perform(get("/actuator"))
			.andExpect(status().isOk());
	}

	/** §4-2 공개 · 무효(위조 서명) → 200 — 공개 경로는 자격 증명을 무시한다 */
	@Test
	@DisplayName("공개 경로는 위조 토큰이 실려 있어도 무시하고 200 이다")
	void 공개경로_위조_토큰은_무시된다() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, forgedBearer()))
			.andExpect(status().isOk());
	}

	/** §4-2 공개 · 만료 → 200 — 만료조차 공개 경로에서는 401 사유가 아니다 */
	@Test
	@DisplayName("공개 경로는 만료 토큰이 실려 있어도 200 이다")
	void 공개경로_만료_토큰도_무시된다() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, expiredBearer()))
			.andExpect(status().isOk());
	}

	/** §4-2 공개 · 정상 → 200 */
	@Test
	@DisplayName("공개 경로는 정상 토큰이 있어도 200 이다")
	void 공개경로_정상_토큰() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());
	}

	// ── 인증 필요 경로 (auth.md §4-2 "인증 필요" 행) ───────────────────────────

	/**
	 * U3 · §4-2 인증 필요 · 자격 증명 없음 → 401 {@code AUTH_001}.
	 * 프론트는 이 코드를 보고 부트스트랩을 재실행한다(§6-2) — 코드가 바뀌면 그 분기가 죽는다.
	 */
	@Test
	@DisplayName("보호 경로는 Authorization 헤더가 없으면 401 AUTH_001 이다")
	void 보호경로_헤더_없음() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_001.getMessage()));
	}

	/** 공백 헤더는 "헤더 없음"과 동일 취급 — AUTH_002 가 아니라 AUTH_001 이다(필터 MISSING 축) */
	@Test
	@DisplayName("보호 경로에 공백 Authorization 을 보내면 401 AUTH_001 이다")
	void 보호경로_공백_헤더() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "   "))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	/**
	 * §4-2 (v4) — 인증 필요 엔드포인트에 {@code X-Anonymous-Key} 를 보내도 <b>무시된다</b>
	 * (auth.md §5-1 "자격 증명은 Bearer 뿐"). 구 헤더 병행 없음이 확정이라, 익명키가 게이트를
	 * 통과시키면 그게 v4 위반이다.
	 */
	@Test
	@DisplayName("보호 경로에서 X-Anonymous-Key 는 자격 증명이 아니다 — 401 AUTH_001")
	void 보호경로_익명키는_무시된다() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header("X-Anonymous-Key", "toss-anon-hash-0123456789abcdef"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	/**
	 * U8 · §4-2 인증 필요 · 무효(서명 위조) → 401 {@code AUTH_002}.
	 * 프론트는 재시도가 무익하므로 부트스트랩 재로그인한다(§6-2) — AUTH_004 와 섞이면
	 * 무한 refresh 루프가 된다.
	 */
	@Test
	@DisplayName("보호 경로에 위조 서명 토큰을 보내면 401 AUTH_002 다")
	void 보호경로_위조_서명() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, forgedBearer()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_002.getMessage()));
	}

	/** §4-2 무효(형식 불량 — JWT 도 아닌 값) → 401 AUTH_002 */
	@Test
	@DisplayName("보호 경로에 JWT 형식이 아닌 Bearer 값을 보내면 401 AUTH_002 다")
	void 보호경로_형식_불량() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
	}

	/** §4-2 무효(Bearer 스킴이 아님) → 401 AUTH_002 — 필터 MALFORMED 축 */
	@Test
	@DisplayName("Bearer 가 아닌 인증 스킴은 401 AUTH_002 다")
	void 보호경로_다른_스킴() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwdw=="))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()));
	}

	/**
	 * §4-2 (v4) 인증 필요 · <b>만료 → 401 {@code AUTH_004}</b> — AUTH_002 와 갈라야 하는 이유:
	 * 프론트 행동이 다르다. AUTH_004 → refresh 후 1회 재시도, AUTH_002 → 재로그인.
	 * 만료가 AUTH_002 에 섞이면 <b>30분마다 전 사용자가 재로그인</b>하게 된다.
	 */
	@Test
	@DisplayName("보호 경로에 만료 토큰을 보내면 AUTH_002 가 아니라 401 AUTH_004 다")
	void 보호경로_만료() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(HttpHeaders.AUTHORIZATION, expiredBearer()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_004.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_004.getMessage()));
	}

	/**
	 * U8 · §4-2 인증 필요 · 정상 → 200. <b>검증은 서명·만료뿐 — DB 를 보지 않는다</b>:
	 * users 테이블에 없는 userId 의 토큰도 서명만 맞으면 통과해야 한다. 게이트가 사용자 존재를
	 * 확인하기 시작하면 "매 요청 DB 조회 0회"(v4 전환의 목적 그 자체)가 무너진다.
	 */
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

	/**
	 * auth-design.md §7 — default-deny 로 <b>존재하지 않는 새 경로도</b> 기본 401 이다.
	 * "아무것도 하지 않으면 401"이 이후 모든 도메인에 걸리는 선례다.
	 */
	@Test
	@DisplayName("열거되지 않은 경로는 기본이 인증 필요다 — 공개는 명시적 열거뿐이다")
	void 기본값은_인증_필요다() throws Exception {
		mockMvc.perform(get("/api/v1/anything-else"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	// ── CORS preflight (auth.md v2 §4-2 ⑪ — v4 에서도 그대로다) ────────────────

	/**
	 * v2 §4-2 — preflight 는 게이트를 거치지 않는다. 브라우저는 preflight 에
	 * {@code Authorization} 을 싣지 않으므로 원리상 인증될 수 없고, 게이트에 걸면
	 * 크로스 오리진 호출이 전부 막힌다. 경로가 아니라 <b>메서드 축</b>의 예외다.
	 */
	@Test
	@DisplayName("CORS preflight 는 토큰이 없어도 401 이 아니다")
	void preflight_는_게이트를_거치지_않는다() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
	}

	/**
	 * preflight 를 열었다고 <b>실제 요청까지 열리면 그게 구멍이다.</b>
	 * Origin 이 붙은 진짜 요청은 토큰이 없으면 여전히 401 이어야 한다(round-2-dev.md 실측 #9).
	 */
	@Test
	@DisplayName("preflight 를 열어도 실제 크로스 오리진 요청은 토큰 없이는 401 이다")
	void preflight_가_인증_구멍을_내지_않는다() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	/**
	 * auth-design.md §7 — {@code WebConfig} 의 CORS 정책은 v4 에서도 "변경 없음"이다.
	 * 허용되지 않은 오리진은 preflight 단계에서 그대로 막혀야 한다(round-2-dev.md 실측 #10).
	 */
	@Test
	@DisplayName("허용되지 않은 오리진의 preflight 는 여전히 막힌다 — CORS 정책은 그대로다")
	void 허용되지_않은_오리진은_막힌다() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
				.header("Origin", "http://evil.example.com")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isForbidden());
	}
}
