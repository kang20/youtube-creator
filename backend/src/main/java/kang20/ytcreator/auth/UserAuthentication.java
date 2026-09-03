package kang20.ytcreator.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class UserAuthentication extends AbstractAuthenticationToken {

	private final UserId userId;
	private final Role role;

	public UserAuthentication(UserId userId) {
		this(userId, Role.USER);
	}

	public UserAuthentication(UserId userId, Role role) {
		super(authoritiesOf(role));
		this.userId = userId;
		this.role = role;
		setAuthenticated(true);
	}

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
