package kang20.ytcreator.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * U6(익명키 비노출)의 <b>인증 객체 축</b> — 코드리뷰 라운드 4 필수-1.
 *
 * <p>이 클래스에는 <b>서로 반대 방향인 두 계약</b>이 함께 있고, 뒤바뀌면 각각 다르게 망가진다.
 *
 * <table border="1">
 *   <tr><th>축</th><th>값</th><th>뒤바뀌면</th></tr>
 *   <tr><td>{@code getPrincipal()} · {@code getAnonymousKey()}</td><td><b>원문</b></td>
 *       <td>식별에 쓰는 값이라 마스킹하면 <b>인증·사용자 조회가 깨진다</b></td></tr>
 *   <tr><td>{@code toString()}</td><td><b>마스킹</b></td>
 *       <td>로그에 원문이 샌다 — 그것이 필수-1 이었다</td></tr>
 * </table>
 *
 * <p>둘을 한 파일에서 같이 못 박는 이유가 이것이다. "원문을 감추자"는 선의로
 * {@code getPrincipal()} 까지 마스킹하면 서비스가 조용히 다른 사용자를 만든다.
 */
class AnonymousAuthenticationTest {

	/** 앞 4자(`toss`) 밖에 표식을 둔다 — 마스킹 결과에 표식이 섞이지 않아야 단언이 정확해진다. */
	private static final String RAW = "toss-anon-SECRET-MARKER-0123456789";

	private final AnonymousAuthentication authentication = new AnonymousAuthentication(RAW);

	/**
	 * U6 · auth.md §4-5 — 문자열 표현에 익명키 원문이 없다.
	 *
	 * <p>상위 {@code AbstractAuthenticationToken.toString()} 은 Credentials 만 가리고
	 * {@code Principal=} 뒤에는 값을 그대로 붙인다. Spring Security 의
	 * {@code AnonymousAuthenticationFilter} 가 <b>인증된 요청마다</b> 이 객체를 TRACE 로 찍으므로,
	 * 재정의를 지우는 순간 로거 레벨 한 줄로 전 요청의 익명키가 로그에 남는다.
	 */
	@Test
	@DisplayName("toString 에는 익명키 원문이 없고 마스킹된 값만 있다")
	void toString_에_원문이_없다() {
		String text = authentication.toString();

		assertThat(text)
			.as("auth.md §4-5 — 로그·예외 메시지 어디에도 원문을 남기지 않는다")
			.doesNotContain(RAW)
			.doesNotContain("SECRET-MARKER")
			.doesNotContain("0123456789")
			.contains(AnonymousKeyFormat.mask(RAW));
	}

	/**
	 * ⚠️ <b>가린 것은 표현뿐이다.</b> 식별 값까지 마스킹하면 서로 다른 사용자가 같은 앞 4자로 뭉쳐
	 * <b>남의 계정으로 들어간다</b>(마스킹은 단사가 아니다).
	 */
	@Test
	@DisplayName("getPrincipal·getAnonymousKey 는 여전히 익명키 원문을 돌려준다")
	void 식별_값은_원문_그대로다() {
		assertThat(authentication.getPrincipal())
			.as("이 값으로 사용자를 식별한다 — 마스킹하면 인증이 깨진다")
			.isEqualTo(RAW);
		assertThat(authentication.getAnonymousKey()).isEqualTo(RAW);

		assertThat(authentication.getCredentials()).isNull();
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getAuthorities())
			.extracting(Object::toString)
			.containsExactly(AnonymousAuthentication.ROLE);
	}

	/**
	 * 마스킹으로 <b>진단 가치를 잃지 않았다.</b> 상위 구현의 필드 구성을 그대로 두고 값 하나만 바꿨다 —
	 * 인증 여부·권한은 여전히 로그에서 읽힌다(그래서 "로그를 끄는" 해법보다 낫다, blockers B4 참조).
	 */
	@Test
	@DisplayName("toString 은 인증 여부·권한 같은 진단 정보를 그대로 유지한다")
	void 진단_정보는_그대로_남는다() {
		String text = authentication.toString();

		assertThat(text)
			.contains(AnonymousAuthentication.class.getSimpleName())
			.contains("Principal=")
			.contains("Credentials=[PROTECTED]")
			.contains("Authenticated=true")
			.contains(AnonymousAuthentication.ROLE);
	}

	/**
	 * U6 경계 — 앞 4자보다 짧은 익명키는 <b>앞 4자가 곧 원문</b>이라 아무것도 남기지 않아야 한다.
	 * {@code toString()} 이 {@code mask()} 를 거치지 않고 직접 자르면 여기서 샌다.
	 */
	@Test
	@DisplayName("4자 미만 익명키도 toString 에 원문이 드러나지 않는다")
	void 짧은_익명키도_새지_않는다() {
		String shortKey = "abc";

		assertThat(new AnonymousAuthentication(shortKey).toString())
			.doesNotContain(shortKey)
			.contains(AnonymousKeyFormat.mask(shortKey));
	}
}
