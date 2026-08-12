package kang20.ytcreator.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * JWT 게이트를 통과한 요청 주체 — principal 은 서명 검증만으로 확정된 {@link UserId} 다
 * (U8 — DB 조회 없음. auth-design.md §14-2, youngZZ {@code AnonymousAuthentication} 동형).
 *
 * <p>게이트 부품이 {@code shared/security} 가 아니라 auth 모듈 루트에 있는 이유는 §14-1 —
 * 토큰 발급·검증·회전은 명백히 auth 도메인이고, {@code shared → auth} 는 순환이라 불가능하다.
 * {@code config}(SecurityConfig)가 이 공개 계약을 조립한다.
 *
 * <p>v1 의 {@code AnonymousAuthentication} 과 달리 <b>toString 마스킹이 필요 없다</b> —
 * principal 이 익명키 원문이 아니라 {@code UserId(n)} 라 로그에 남아도 U6 위반이 아니다.
 */
public class UserAuthentication extends AbstractAuthenticationToken {

	public static final String ROLE = "ROLE_USER";

	private static final Collection<GrantedAuthority> AUTHORITIES =
		List.of(new SimpleGrantedAuthority(ROLE));

	private final UserId userId;

	public UserAuthentication(UserId userId) {
		super(AUTHORITIES);
		this.userId = userId;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return null;    // 토큰은 검증 후 버린다 — 요청 주체만 남긴다
	}

	@Override
	public Object getPrincipal() {
		return userId;
	}

	public UserId getUserId() {
		return userId;
	}
}
