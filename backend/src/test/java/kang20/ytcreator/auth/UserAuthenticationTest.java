package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

/**
 * 게이트가 만드는 인증 객체의 계약 — auth-design.md §14-2 {@code UserAuthentication}
 * (§14-5 "삭제된 {@code AnonymousAuthenticationTest} 는 대체 부품의 테스트가 승계").
 *
 * <p>v1 과 달리 <b>toString 마스킹 검증이 없다</b>(round-1-dev.md 판단 9) — principal 이
 * 익명키 원문이 아니라 {@code UserId(n)} 라 로그에 찍혀도 U6 위반이 아니기 때문이다.
 * 그 전제가 바로 아래 {@code principal_은_익명키가_아니라_UserId_다} 가 지키는 것이다.
 */
class UserAuthenticationTest {

	private static final UserId USER = new UserId(42L);

	private final UserAuthentication authentication = new UserAuthentication(USER);

	/**
	 * U8 — 게이트 통과 즉시 인증 완료 상태다(추가 검증 단계 없음). {@code SecurityConfig} 의
	 * {@code authenticated()} 판정이 이 플래그를 본다 — false 면 정상 토큰도 전부 401 이 된다.
	 */
	@Test
	@DisplayName("생성 즉시 인증된 상태이고 권한은 ROLE_USER 하나다")
	void 인증_상태와_권한() {
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly(Role.USER.authority());
	}

	/**
	 * ADMIN 은 {@code ROLE_ADMIN} <b>하나</b>다 — ROLE_USER 를 겸하지 않는다.
	 * 겸하게 만들면 "일반 사용자만" 규칙을 표현할 수 없고, 계층이 필요하면
	 * {@code RoleHierarchy} 로 명시하는 것이 맞다.
	 */
	@Test
	@DisplayName("ADMIN 의 권한은 ROLE_ADMIN 하나다")
	void 어드민_권한() {
		UserAuthentication admin = new UserAuthentication(USER, Role.ADMIN);

		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.getAuthorities())
			.extracting(GrantedAuthority::getAuthority)
			.containsExactly("ROLE_ADMIN");
	}

	/**
	 * U8 — 요청 주체는 서명 검증으로 확정된 {@code UserId} 다. principal 에 익명키·토큰 원문이
	 * 들어오는 변경은 U6 마스킹 의무를 되살리므로 여기서 먼저 빨개져야 한다(판단 9 의 전제).
	 */
	@Test
	@DisplayName("principal 은 익명키가 아니라 UserId 다 — 마스킹 면제의 전제")
	void principal_은_익명키가_아니라_UserId_다() {
		assertThat(authentication.getPrincipal()).isEqualTo(USER);
		assertThat(authentication.getUserId()).isEqualTo(USER);
		// toString(로그에 찍히는 형태)에도 원시 식별자 표현뿐이다 — UserId(42)
		assertThat(String.valueOf(authentication.getPrincipal())).isEqualTo("UserId(42)");
	}

	/** §14-2 — credentials 는 null 이다. 토큰은 검증 후 버린다 — 인증 객체가 토큰 보관소가 되면 안 된다. */
	@Test
	@DisplayName("credentials 는 null 이다 — 토큰은 검증 후 버린다")
	void credentials_는_null_이다() {
		assertThat(authentication.getCredentials()).isNull();
	}
}
