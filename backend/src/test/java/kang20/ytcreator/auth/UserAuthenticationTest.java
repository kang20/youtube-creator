package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

class UserAuthenticationTest {

	private static final UserId USER = new UserId(42L);

	private final UserAuthentication authentication = new UserAuthentication(USER);

	@Test
	@DisplayName("생성 즉시 인증된 상태이고 권한은 ROLE_USER 하나다")
	void 인증_상태와_권한() {
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly(Role.USER.authority());
	}

	@Test
	@DisplayName("ADMIN 의 권한은 ROLE_ADMIN 하나다")
	void 어드민_권한() {
		UserAuthentication admin = new UserAuthentication(USER, Role.ADMIN);

		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_ADMIN");
	}

	@Test
	@DisplayName("principal 은 익명키가 아니라 UserId 다 — 마스킹 면제의 전제")
	void principal_은_익명키가_아니라_UserId_다() {
		assertThat(authentication.getPrincipal()).isEqualTo(USER);
		assertThat(authentication.getUserId()).isEqualTo(USER);
		// toString(로그에 찍히는 형태)에도 원시 식별자 표현뿐이다 — UserId(42)
		assertThat(String.valueOf(authentication.getPrincipal())).isEqualTo("UserId(42)");
	}

	@Test
	@DisplayName("credentials 는 null 이다 — 토큰은 검증 후 버린다")
	void credentials_는_null_이다() {
		assertThat(authentication.getCredentials()).isNull();
	}
}
