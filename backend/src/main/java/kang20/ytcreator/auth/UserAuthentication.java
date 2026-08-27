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

	private final UserId userId;
	private final Role role;

	/** 권한 생략은 {@link Role#USER} — 일반 사용자 경로가 대부분이다. */
	public UserAuthentication(UserId userId) {
		this(userId, Role.USER);
	}

	public UserAuthentication(UserId userId, Role role) {
		super(authoritiesOf(role));
		this.userId = userId;
		this.role = role;
		setAuthenticated(true);
	}

	/**
	 * authority 는 <b>정확히 하나</b>다 — ADMIN 이 ROLE_USER 를 겸하지 않는다.
	 * 계층(ADMIN ⊃ USER)이 필요하면 {@code RoleHierarchy} 빈으로 명시하는 것이 맞고,
	 * 여기서 조용히 두 개를 넣으면 "일반 사용자만" 같은 규칙을 표현할 수 없게 된다.
	 */
	private static Collection<GrantedAuthority> authoritiesOf(Role role) {
		return List.of(new SimpleGrantedAuthority(role.authority()));
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

	public Role getRole() {
		return role;
	}
}
