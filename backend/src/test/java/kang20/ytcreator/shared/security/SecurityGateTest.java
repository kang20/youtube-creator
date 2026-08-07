package kang20.ytcreator.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * U3(인증 게이트) 통합 — auth.md §4-2 의 "엔드포인트 분류 × 헤더 상태" 표를 그대로 검증한다.
 *
 * <p>auth 에는 컨트롤러가 없으므로(auth.md §5-3) 게이트를 관측할 엔드포인트가 없다.
 * 그래서 <b>테스트 전용 보호 경로</b>를 하나 띄운다. 운영 코드에 문서화용 더미 컨트롤러를 만들지
 * 않는다는 auth-design.md §8 결정과 어긋나지 않는다 — 이건 {@code src/test} 안에만 있고
 * REST Docs 스니펫도 만들지 않는다(게이트 401 문서화는 {@code bootstrap} 구현 시, §8).
 *
 * <p>⚠️ {@code @WebMvcTest} 슬라이스가 아니라 {@code @SpringBootTest} 인 이유: CORS preflight 는
 * {@code WebConfig} 의 오리진 정책이 함께 올라와야 재현된다(round-2-dev.md 테스터 노트).
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

	/** WebConfig 기본 허용 오리진(로컬 개발 서버). CORS 정책은 이번 변경에서 손대지 않았다. */
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;

	/**
	 * 게이트를 관측하기 위한 테스트 전용 보호 경로. 함수형 엔드포인트라 컴포넌트 스캔에 잡히지 않는다.
	 */
	@TestConfiguration
	static class ProbeEndpoint {

		@Bean
		RouterFunction<ServerResponse> gateProbeRoute() {
			return RouterFunctions.route()
				.GET(PROTECTED_PATH, request -> ServerResponse.ok().body("ok"))
				.build();
		}
	}

	// ── 공개 경로 (auth.md §4-2 "공개" 행) ─────────────────────────────────────

	/** §4-2 공개 · 헤더 없음 → 200 */
	@Test
	@DisplayName("공개 경로는 익명키 헤더 없이 200 이다")
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

	/** §4-2 공개 · 헤더 있음·형식 위반 → 200 (익명키를 아예 보지 않는다) */
	@Test
	@DisplayName("공개 경로는 형식이 틀린 익명키가 실려 있어도 무시하고 200 이다")
	void 공개경로_형식_위반_헤더는_무시된다() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.tooLong()))
			.andExpect(status().isOk());
	}

	/** §4-2 공개 · 헤더 정상 → 200 */
	@Test
	@DisplayName("공개 경로는 정상 익명키가 있어도 200 이다")
	void 공개경로_정상_헤더() throws Exception {
		mockMvc.perform(get(PUBLIC_HEALTH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.VALID))
			.andExpect(status().isOk());
	}

	// ── 인증 필요 경로 (auth.md §4-2 "인증 필요" 행) ───────────────────────────

	/**
	 * U3 · §4-2 인증 필요 · 헤더 없음 → 401 {@code AUTH_001}.
	 * 프론트는 이 코드를 보고 SDK 를 1회 재호출한다(§6-2) — 코드가 바뀌면 그 분기가 죽는다.
	 */
	@Test
	@DisplayName("보호 경로는 익명키 헤더가 없으면 401 AUTH_001 이다")
	void 보호경로_헤더_없음() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_001.getMessage()));
	}

	/** §4-4 — 공백 헤더는 "헤더 없음"과 동일 취급이라 AUTH_002 가 아니라 AUTH_001 이다 */
	@Test
	@DisplayName("보호 경로에 공백 헤더를 보내면 AUTH_002 가 아니라 401 AUTH_001 이다")
	void 보호경로_공백_헤더() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.BLANK))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	/**
	 * U5 · §4-2 인증 필요 · 형식 위반 → 401 {@code AUTH_002}.
	 * 프론트는 재호출해도 소용없으므로 안내 후 종료한다(§6-1) — AUTH_001 과 섞이면 무한 재시도가 된다.
	 */
	@Test
	@DisplayName("보호 경로에 형식이 틀린 익명키를 보내면 401 AUTH_002 다")
	void 보호경로_형식_위반() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.tooLong()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode()))
			.andExpect(jsonPath("$.message").value(ErrorCode.AUTH_002.getMessage()));
	}

	/** §4-2 인증 필요 · 헤더 정상 → 200. 길이 상한 경계값도 통과해야 한다(§12-2) */
	@Test
	@DisplayName("보호 경로는 정상 익명키가 있으면 200 이다")
	void 보호경로_정상_헤더() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.VALID))
			.andExpect(status().isOk())
			.andExpect(content().string("ok"));

		mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.atMaxLength()))
			.andExpect(status().isOk());
	}

	/**
	 * auth-design.md §7 — default-deny 로 바뀌었으므로 <b>존재하지 않는 새 경로도</b> 기본 401 이다.
	 * "아무것도 하지 않으면 401"이 이후 모든 도메인에 걸리는 선례다.
	 */
	@Test
	@DisplayName("열거되지 않은 경로는 기본이 인증 필요다 — 공개는 명시적 열거뿐이다")
	void 기본값은_인증_필요다() throws Exception {
		mockMvc.perform(get("/api/v1/anything-else"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	// ── CORS preflight (auth.md v2 §4-2 ⑪ · blockers B2) ──────────────────────

	/**
	 * v2 §4-2 — preflight 는 게이트를 거치지 않는다. 브라우저는 preflight 에
	 * {@code X-Anonymous-Key} 를 싣지 않으므로 원리상 인증될 수 없고, 게이트에 걸면
	 * 크로스 오리진 호출이 전부 막힌다. 경로가 아니라 <b>메서드 축</b>의 예외다.
	 */
	@Test
	@DisplayName("CORS preflight 는 익명키가 없어도 401 이 아니다")
	void preflight_는_게이트를_거치지_않는다() throws Exception {
		mockMvc.perform(options(PROTECTED_PATH)
				.header("Origin", ALLOWED_ORIGIN)
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
	}

	/**
	 * preflight 를 열었다고 <b>실제 요청까지 열리면 그게 구멍이다.</b>
	 * Origin 이 붙은 진짜 요청은 익명키가 없으면 여전히 401 이어야 한다(round-2-dev.md 실측 #9).
	 */
	@Test
	@DisplayName("preflight 를 열어도 실제 크로스 오리진 요청은 익명키 없이는 401 이다")
	void preflight_가_인증_구멍을_내지_않는다() throws Exception {
		mockMvc.perform(get(PROTECTED_PATH).header("Origin", ALLOWED_ORIGIN))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_001.getCode()));
	}

	/**
	 * auth-design.md §7 — {@code WebConfig} 는 "변경 없음이 정책"이다.
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

	// ── U6 익명키 비노출 ──────────────────────────────────────────────────────

	/** U6 · §5-2 — 에러 응답에 익명키를 되돌려주지 않는다. 앞 4자조차 본문에 없다. */
	@Test
	@DisplayName("401 응답 본문에 익명키 원문이 실리지 않는다")
	void 응답_본문에_익명키가_없다() throws Exception {
		String raw = AnonymousKeyFixture.tooLong();

		MvcResult result = mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, raw))
			.andExpect(status().isUnauthorized())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(raw);
		assertThat(body).doesNotContain(raw.substring(0, 8));
		assertThat(result.getResponse().getHeaderNames())
			.noneSatisfy(name -> assertThat(result.getResponse().getHeader(name)).contains(raw));
	}

	/** 로그 누출을 잡기 위한 표식. 이 문자열이 로그에 보이면 익명키가 샌 것이다. */
	private static final String LEAK_MARKER = "SECRET-ANON-MARKER";

	/**
	 * TRACE 회귀 테스트가 여는 로거. <b>루트가 아니라 여기로 좁힌다</b> —
	 * 이유는 {@link #인증_객체가_TRACE_로_찍혀도_원문이_남지_않는다()} 참조.
	 */
	private static final String SPRING_SECURITY_LOGGER = "org.springframework.security";

	/**
	 * U6 · §4-5 — <b>로그에도</b> 익명키 원문을 남기지 않는다.
	 * 익명키를 아는 것이 곧 그 사용자가 되는 것이므로(§4-1) 노출 최소화가 유일하게 실효 있는 보호다.
	 * 게이트가 401 을 내는 동안 루트 로거를 DEBUG 로 열어 전부 받아 본다(이 경로엔 DB 접근이 없다).
	 */
	@Test
	@DisplayName("형식 위반으로 401 을 내는 동안 어떤 로그에도 익명키 원문이 남지 않는다")
	void 거부할_때_로그에_익명키가_남지_않는다() throws Exception {
		String raw = LEAK_MARKER + "-" + "0123456789".repeat(30);   // 형식 위반(길이 초과) → AUTH_002

		var captured = captureLogsWhile(() ->
			mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, raw))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.AUTH_002.getCode())));

		assertThat(captured)
			.as("auth.md §4-5 — 추적이 필요하면 AnonymousKeyFormat.mask() 를 거친 값만 남긴다")
			.noneMatch(message -> message.contains(raw))
			.noneMatch(message -> message.contains(LEAK_MARKER));
	}

	/**
	 * U6 — <b>통과시킬 때가 더 위험하다.</b> 인증에 성공하면 익명키가 {@code SecurityContext} 에
	 * 들어가므로, 그 인증 객체를 통째로 찍는 로그가 하나라도 있으면 원문이 샌다(§4-1).
	 */
	@Test
	@DisplayName("정상 익명키로 통과할 때도 로그에 원문이 남지 않는다")
	void 통과시킬_때도_로그에_익명키가_남지_않는다() throws Exception {
		String raw = LEAK_MARKER + "-valid-key";

		var captured = captureLogsWhile(() ->
			mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, raw))
				.andExpect(status().isOk()));

		assertThat(captured)
			.noneMatch(message -> message.contains(raw))
			.noneMatch(message -> message.contains(LEAK_MARKER));
	}

	/**
	 * ⭐ <b>코드리뷰 라운드 4 필수-1 의 회귀 방지선.</b>
	 *
	 * <p>위 두 테스트는 루트를 <b>DEBUG</b> 까지만 연다. 그런데 실제 유출 경로는 <b>TRACE</b> 였다 —
	 * Spring Security 의 {@code AnonymousAuthenticationFilter} 가 <b>이미 인증된 요청마다</b>
	 * 인증 객체를 TRACE 로 찍고("Did not set SecurityContextHolder since already authenticated ..."),
	 * 재정의 전 {@code AnonymousAuthentication.toString()} 은 {@code Principal=} 뒤에 원문을 그대로 붙였다.
	 * 즉 <b>DEBUG 까지만 여는 테스트로는 원리상 잡히지 않는 구멍</b>이었다.
	 *
	 * <p>{@code logging.level.org.springframework.security: TRACE} 한 줄이면 인증된 전 요청의 익명키가
	 * Loki 14일 + gz 영구 아카이브에 남는다. blockers <b>B4 와 같은 실패 유형</b>이다 —
	 * 우리 코드가 찍지 않아도 프레임워크가 찍는다.
	 *
	 * <p>⚠️ <b>로거 범위를 {@code org.springframework.security} 로 좁힌다.</b> 루트를 TRACE 로 올리면
	 * 서블릿 컨테이너의 raw HTTP 덤프(Tomcat {@code Http11InputBuffer})가 수신 헤더를 통째로 찍어
	 * <b>우리 코드와 무관하게</b> 빨개진다(round-4-dev.md 관찰 1 — 성격이 다른 별개 항목이다).
	 *
	 * <p>⚠️ <b>"마스킹 값이 보인다"를 함께 단언하는 이유</b>: 원문 부재만 보면
	 * 그 TRACE 로그가 <b>아예 없어져도</b> 통과한다(공허한 통과). 마스킹이 보인다는 것은
	 * <b>같은 자리에 같은 로그가 그대로 찍혔고 값만 바뀌었다</b>는 증거다.
	 * 이 단언이 깨지면 Spring Security 가 그 로그를 더는 찍지 않는다는 뜻이니,
	 * "고쳐졌다"가 아니라 <b>회귀 방지선이 무효가 됐다</b>고 읽고 사람이 확인해야 한다.
	 */
	@Test
	@DisplayName("security 로거를 TRACE 로 열어도 인증 객체가 익명키 원문을 흘리지 않는다")
	void 인증_객체가_TRACE_로_찍혀도_원문이_남지_않는다() throws Exception {
		String raw = LEAK_MARKER + "-trace-probe";
		String masked = AnonymousKeyFormat.mask(raw);

		var captured = captureLogsWhile(SPRING_SECURITY_LOGGER, Level.TRACE, () ->
			mockMvc.perform(get(PROTECTED_PATH).header(AnonymousKeyFilter.HEADER, raw))
				.andExpect(status().isOk()));

		assertThat(captured)
			.as("auth.md §3 U6 · §4-5 — 인증된 요청마다 찍히는 로그다. 원문이면 전 사용자가 샌다")
			.noneMatch(message -> message.contains(raw))
			.noneMatch(message -> message.contains(LEAK_MARKER));

		assertThat(captured)
			.as("인증 객체는 여전히 로그에 찍혀야 한다(값만 마스킹) — 안 보이면 이 테스트는 공허하다")
			.anyMatch(message -> message.contains(masked));
	}

	/** 루트 로거를 DEBUG 로 열어 두고 한 요청 동안의 로그를 전부 모은다. 레벨은 반드시 되돌린다. */
	private java.util.List<String> captureLogsWhile(ThrowingRunnable action) throws Exception {
		return captureLogsWhile(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.DEBUG, action);
	}

	/**
	 * 지정한 로거를 지정 레벨로 잠깐 열고 그 동안의 로그를 모은다. 레벨은 반드시 되돌린다.
	 *
	 * <p>하위 로거의 이벤트도 이 로거의 appender 로 올라오고, 하위의 실효 레벨은 여기서 정한 값을
	 * 상속하므로 <b>범위를 좁히는 것만으로 관심 밖 로거의 소음을 차단</b>할 수 있다.
	 */
	private java.util.List<String> captureLogsWhile(String loggerName, Level level, ThrowingRunnable action)
			throws Exception {
		ch.qos.logback.classic.Logger logger =
			(ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level original = logger.getLevel();

		logger.addAppender(appender);
		logger.setLevel(level);
		try {
			action.run();
		} finally {
			logger.setLevel(original);   // null 이면 상위 상속으로 되돌아간다
			logger.detachAppender(appender);
			appender.stop();
		}
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
