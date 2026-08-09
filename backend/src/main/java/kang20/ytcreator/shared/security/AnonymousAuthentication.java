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
	 * ⚠️ <b>지우지 마라.</b> 상위 구현은 {@code Principal=} 뒤에 값을 그대로 붙이고,
	 * Spring Security 는 인증된 요청마다 이 객체를 TRACE 로 찍는다 — 재정의하지 않으면
	 * <b>로거 레벨 한 줄로 전 요청의 익명키가 로그에 남는다</b>(U6).
	 *
	 * <p>가리는 것은 <b>문자열 표현뿐</b>이다 — {@link #getPrincipal()} 은 실제 식별에 쓰이므로
	 * 원문을 그대로 돌려준다. 둘을 뒤바꾸면 인증이 깨진다.
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
