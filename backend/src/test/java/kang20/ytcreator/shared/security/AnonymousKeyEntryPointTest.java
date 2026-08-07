package kang20.ytcreator.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * U3(인증 게이트)의 401 응답 계약.
 *
 * <p>이 401 은 보안 필터 체인 안에서 끝나 {@code GlobalExceptionHandler} 에 도달하지 않는다
 * (auth-design.md §2-1 쟁점 1). 그래서 공통 에러 본문 {@code {code, message}} 을 진입점이
 * <b>직접</b> 써야 하고, 그러지 않으면 auth.md §6-1·§6-2 의 프론트 분기(AUTH_001 재호출 /
 * AUTH_002 종료)가 전부 죽는다.
 */
class AnonymousKeyEntryPointTest {

	/** Boot 4 = Jackson 3. 진입점은 컨테이너의 JsonMapper 를 주입받는다(round-1-dev.md). */
	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private final AnonymousKeyEntryPoint entryPoint = new AnonymousKeyEntryPoint(objectMapper);

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private MockHttpServletRequest requestWith(AnonymousKeyFilter.Denial denial) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (denial != null) {
			request.setAttribute(AnonymousKeyFilter.DENIAL_ATTRIBUTE, denial);
		}
		return request;
	}

	private void commence(MockHttpServletRequest request) throws Exception {
		entryPoint.commence(request, response, new InsufficientAuthenticationException("no auth"));
	}

	/** U3 — 형식 위반은 AUTH_002. 프론트는 재시도해도 소용없으므로 안내 후 종료한다(auth.md §4-2·§6-2) */
	@Test
	@DisplayName("거부 사유가 MALFORMED 면 401 AUTH_002 를 준다")
	void 형식_위반은_AUTH_002() throws Exception {
		commence(requestWith(AnonymousKeyFilter.Denial.MALFORMED));

		assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(response.getContentAsString())
			.contains("\"code\":\"AUTH_002\"")
			.contains(ErrorCode.AUTH_002.getMessage());
	}

	/** U3 — 헤더 누락은 AUTH_001. 프론트는 SDK 를 1회 재호출한다(auth.md §4-2·§6-2) */
	@Test
	@DisplayName("거부 사유가 MISSING 이면 401 AUTH_001 을 준다")
	void 헤더_누락은_AUTH_001() throws Exception {
		commence(requestWith(AnonymousKeyFilter.Denial.MISSING));

		assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(response.getContentAsString())
			.contains("\"code\":\"AUTH_001\"")
			.contains(ErrorCode.AUTH_001.getMessage());
	}

	/**
	 * 필터를 타지 않은 경로(다른 인증 예외 등)로 진입점이 불릴 수도 있다.
	 * auth-design.md §5-2 는 "그 외(MISSING 포함) → AUTH_001" 로 기본값을 정했다.
	 */
	@Test
	@DisplayName("거부 사유 attribute 가 아예 없으면 기본값 AUTH_001 이다")
	void 거부사유가_없으면_AUTH_001() throws Exception {
		commence(requestWith(null));

		assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
		assertThat(response.getContentAsString()).contains("\"code\":\"AUTH_001\"");
	}

	/**
	 * 공통 에러 본문 규격 — {@code {code, message}} 고정(auth.md §5-1 ·
	 * docs/rule/error-handling.md). 필드가 더 붙거나 이름이 바뀌면 프론트 계약이 깨진다.
	 */
	@Test
	@DisplayName("본문은 공통 에러 규격 {code, message} JSON 이고 UTF-8 로 나간다")
	void 공통_에러_본문_규격() throws Exception {
		commence(requestWith(AnonymousKeyFilter.Denial.MALFORMED));

		assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase(StandardCharsets.UTF_8.name());
		assertThat(response.getContentAsString())
			.isEqualTo("{\"code\":\"AUTH_002\",\"message\":\"" + ErrorCode.AUTH_002.getMessage() + "\"}");
	}

	/**
	 * U6 — 요청에 익명키가 실려 있어도 응답 본문에 <b>원문을 되돌려주지 않는다</b>
	 * (auth.md §3 U6 · §4-5 · §5-2 "에러 응답에 익명키를 되돌려주지 않는다").
	 */
	@Test
	@DisplayName("에러 응답 본문에 익명키 원문도, 마스킹 값도 싣지 않는다")
	void 익명키_원문을_응답에_싣지_않는다() throws Exception {
		String raw = AnonymousKeyFixture.tooLong();
		MockHttpServletRequest request = requestWith(AnonymousKeyFilter.Denial.MALFORMED);
		request.addHeader(AnonymousKeyFilter.HEADER, raw);

		commence(request);

		assertThat(response.getContentAsString())
			.doesNotContain(raw)
			.doesNotContain(AnonymousKeyFormat.mask(raw))
			.doesNotContain(raw.substring(0, 4));
	}

	/**
	 * auth.md §4-2 — "403 AUTH_003 은 auth 도메인이 쓰지 않는다. 인가 판정은 subscription 몫이다."
	 * 진입점이 낼 수 있는 응답은 401 두 종류뿐임을 고정한다.
	 */
	@Test
	@DisplayName("게이트는 403 AUTH_003 을 내지 않는다 — 401 두 종류뿐이다")
	void 게이트는_403을_쓰지_않는다() throws Exception {
		for (AnonymousKeyFilter.Denial denial : AnonymousKeyFilter.Denial.values()) {
			MockHttpServletResponse each = new MockHttpServletResponse();
			MockHttpServletRequest request = requestWith(denial);

			entryPoint.commence(request, each, new InsufficientAuthenticationException("no auth"));

			assertThat(each.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
			assertThat(each.getContentAsString())
				.doesNotContain(ErrorCode.AUTH_003.getCode())
				.matches(".*\"code\":\"AUTH_00[12]\".*");
		}
	}
}
