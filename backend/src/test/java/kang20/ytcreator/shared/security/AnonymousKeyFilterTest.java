package kang20.ytcreator.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * U1(익명키 헤더 수신) · U5(형식 사전 검증)의 필터 쪽.
 *
 * <p>핵심 계약은 <b>"필터는 어떤 경우에도 거부하지 않는다"</b>다
 * (auth-design.md §2-1 쟁점 2 · §5-2). 공개 엔드포인트는 형식이 틀린 익명키도 무시하고 200 을
 * 줘야 하므로, 필터가 401 을 내면 auth.md §4-2 계약이 깨진다.
 * 거부 사유만 request attribute 로 남기고 판정은 인가 규칙 + {@link AnonymousKeyEntryPoint} 가 한다.
 */
class AnonymousKeyFilterTest {

	private final AnonymousKeyFilter filter = new AnonymousKeyFilter();
	private final MockHttpServletResponse response = new MockHttpServletResponse();
	private final FilterChain chain = Mockito.mock(FilterChain.class);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private static Object denialOf(MockHttpServletRequest request) {
		return request.getAttribute(AnonymousKeyFilter.DENIAL_ATTRIBUTE);
	}

	/** U1 — 헤더가 있으면 그 값을 요청 주체로 삼는다 (auth.md §3 U1) */
	@Test
	@DisplayName("익명키 헤더가 있으면 인증 객체가 컨텍스트에 들어간다")
	void 헤더가_있으면_인증된다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, "anon-key-1");

		filter.doFilter(request, response, chain);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isInstanceOf(AnonymousAuthentication.class);
		assertThat(authentication.getPrincipal()).isEqualTo("anon-key-1");
		assertThat(authentication.getCredentials()).isNull();
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getAuthorities())
			.extracting(Object::toString)
			.containsExactly(AnonymousAuthentication.ROLE);
		assertThat(((AnonymousAuthentication) authentication).getAnonymousKey()).isEqualTo("anon-key-1");
		Mockito.verify(chain).doFilter(request, response);
	}

	/** U1 — 헤더가 없으면 "미식별 요청"일 뿐 즉시 401 이 아니다 (auth.md §3 U1 · §4-2) */
	@Test
	@DisplayName("헤더가 없으면 인증 없이 통과시킨다 — 401 은 인가 규칙이 판단한다")
	void 헤더가_없으면_통과한다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		Mockito.verify(chain).doFilter(request, response);
	}

	/** U5 — 공백뿐인 헤더는 "헤더 없음"과 동일 취급이다 (auth.md §4-4) */
	@Test
	@DisplayName("헤더가 공백뿐이면 인증하지 않는다")
	void 공백_헤더는_무시한다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.BLANK);

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	/** 정상 헤더에는 거부 사유를 남기지 않는다 — 진입점이 잘못된 코드를 고르지 않게 (auth-design.md §5-2) */
	@Test
	@DisplayName("형식이 정상이면 거부 사유 attribute 를 남기지 않는다")
	void 정상_헤더는_거부사유가_없다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.VALID);

		filter.doFilter(request, response, chain);

		assertThat(denialOf(request)).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	}

	/** 헤더 없음 → MISSING → AUTH_001 (auth-design.md §5-2 표) */
	@Test
	@DisplayName("헤더가 없으면 거부 사유는 MISSING 이다")
	void 헤더_없음은_MISSING() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();

		filter.doFilter(request, response, chain);

		assertThat(denialOf(request)).isEqualTo(AnonymousKeyFilter.Denial.MISSING);
	}

	/**
	 * 판정 순서 — 공백은 MALFORMED 가 아니라 <b>MISSING</b> 이다.
	 * auth.md §4-4 가 "공백/빈 값은 헤더 없음과 동일 취급(AUTH_001)"으로 확정했다.
	 * 프론트 행동이 갈리므로(§4-2: AUTH_001 → SDK 재호출 / AUTH_002 → 종료) 순서가 계약이다.
	 */
	@Test
	@DisplayName("공백 헤더의 거부 사유는 MALFORMED 가 아니라 MISSING 이다")
	void 공백_헤더는_MISSING() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.BLANK);

		filter.doFilter(request, response, chain);

		assertThat(denialOf(request)).isEqualTo(AnonymousKeyFilter.Denial.MISSING);
	}

	/** U5 — 형식 위반은 MALFORMED. 인증은 설정하지 않는다 (auth-design.md §5-2 표) */
	@Test
	@DisplayName("형식이 틀린 헤더는 인증하지 않고 거부 사유 MALFORMED 를 남긴다")
	void 형식_위반은_MALFORMED() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.tooLong());

		filter.doFilter(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertThat(denialOf(request)).isEqualTo(AnonymousKeyFilter.Denial.MALFORMED);
	}

	/**
	 * ⚠️ 이 도메인의 급소 — <b>필터는 거부하지 않는다</b>(auth-design.md §2-1 쟁점 2).
	 * 여기서 응답을 쓰거나 체인을 끊으면 공개 엔드포인트가 형식 위반 익명키에 200 을 줄 수 없어
	 * auth.md §4-2 "공개 / 헤더 있음·형식 위반 → 200 (익명키 무시)" 계약이 깨진다.
	 */
	@Test
	@DisplayName("형식이 틀려도 응답을 건드리지 않고 체인을 계속 태운다 — 거부는 필터의 일이 아니다")
	void 형식이_틀려도_거부하지_않는다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, AnonymousKeyFixture.tooLong());

		filter.doFilter(request, response, chain);

		Mockito.verify(chain).doFilter(request, response);
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEmpty();
		assertThat(response.isCommitted()).isFalse();
	}

	/** U6 — 필터는 익명키 원문을 응답 어디에도 싣지 않는다 (auth.md §3 U6 · §4-5) */
	@Test
	@DisplayName("익명키 원문을 응답 본문·헤더에 남기지 않는다")
	void 익명키_원문을_노출하지_않는다() throws Exception {
		String raw = AnonymousKeyFixture.tooLong();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(AnonymousKeyFilter.HEADER, raw);

		filter.doFilter(request, response, chain);

		assertThat(response.getContentAsString()).doesNotContain(raw);
		assertThat(response.getHeaderNames()).isEmpty();
	}
}
