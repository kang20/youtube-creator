package kang20.ytcreator.shared.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 토스 미니앱 익명키 인증. 클라의 getAnonymousKey() 값이 곧 사용자 식별자다.
 *
 * <p>비밀이 아니라 <b>식별자</b>이므로 이 인증만으로는 민감 작업을 허용하지 않는다.
 *
 * <p>이 서비스는 <b>전 구간 익명키 단일 식별</b>이다 — 별도 로그인 경로를 두지 않는다.
 */
public class AnonymousAuthentication extends AbstractAuthenticationToken {

	public static final String ROLE = "ROLE_ANONYMOUS_KEY";

	private static final Collection<GrantedAuthority> AUTHORITIES =
		List.of(new SimpleGrantedAuthority(ROLE));

	private final String anonymousKey;

	public AnonymousAuthentication(String anonymousKey) {
		super(AUTHORITIES);
		this.anonymousKey = anonymousKey;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return null;    // 비밀번호에 해당하는 것이 없다
	}

	@Override
	public Object getPrincipal() {
		return anonymousKey;
	}

	public String getAnonymousKey() {
		return anonymousKey;
	}

	/**
	 * ⚠️ <b>문자열 표현에는 익명키 원문을 넣지 않는다</b>(U6).
	 *
	 * <p>상위 {@code AbstractAuthenticationToken.toString()} 은 <b>Credentials 만</b> {@code [PROTECTED]} 로
	 * 가리고 {@code Principal=} 뒤에는 값을 그대로 붙인다. 그리고 Spring Security 의
	 * {@code AnonymousAuthenticationFilter} 는 <b>이미 인증된 요청마다</b> 이 객체를 TRACE 로 찍는다
	 * ("Did not set SecurityContextHolder since already authenticated ...").
	 * 즉 재정의하지 않으면 <b>로거 레벨 한 줄</b>로 인증된 전 요청의 익명키가 로그에 남는다 —
	 * 로그는 Loki 14일 + gz 영구 아카이브라 되돌릴 수 없다(docs/ops/logging.md §3.3).
	 *
	 * <p>blockers B4(제약 위반 메시지에 원문이 실림)와 <b>같은 실패 유형</b>이다:
	 * <b>우리 코드가 찍지 않아도 프레임워크가 찍는다.</b>
	 *
	 * <p>가리는 것은 <b>문자열 표현뿐</b>이다 — {@link #getPrincipal()}·{@link #getAnonymousKey()} 는
	 * 실제 식별에 쓰이므로 원문을 그대로 돌려준다.
	 */
	@Override
	public String toString() {
		return getClass().getSimpleName()
			+ " [Principal=" + AnonymousKeyFormat.mask(anonymousKey)
			+ ", Credentials=[PROTECTED]"
			+ ", Authenticated=" + isAuthenticated()
			+ ", Granted Authorities=" + getAuthorities() + "]";
	}
}
