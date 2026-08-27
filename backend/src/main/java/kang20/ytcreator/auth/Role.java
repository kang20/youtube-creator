package kang20.ytcreator.auth;

/**
 * 사용자 권한 — auth 가 밖에 노출하는 공개 계약이다({@link UserId} 와 같은 축).
 *
 * <p>v1 범위는 <b>둘뿐</b>이다: 일반 사용자와 운영자. 세분화(모더레이터·읽기 전용 등)는
 * 필요해질 때 값을 추가한다 — 지금 만들어 두면 쓰이지 않는 분기만 남는다.
 *
 * <p>⚠️ <b>enum 이름을 바꾸지 마라</b> — DB 에 {@code EnumType.STRING} 으로,
 * JWT 에 {@code role} 클레임 문자열로 그대로 박혀 있다. 이름을 바꾸면 기존 행과
 * 유통 중인 토큰이 전부 깨진다.
 */
public enum Role {

	/** 기본값. 모든 신규 사용자가 여기서 시작한다. */
	USER,

	/** 운영자. 부여는 코드가 아니라 DB 직접 변경으로만 한다(승격 API 없음 — auth.md §4-3). */
	ADMIN;

	/** Spring Security 가 {@code hasRole("ADMIN")} 으로 찾는 authority 문자열. */
	public String authority() {
		return "ROLE_" + name();
	}

	/**
	 * JWT {@code role} 클레임을 값으로 되돌린다.
	 *
	 * <p>모르는 값·null 은 {@link #USER} 다 — 권한 확대가 아니라 축소 방향이라 안전하고,
	 * role 클레임이 없던 시절 발급된 토큰(access 수명 30분)도 이 경로로 흡수된다.
	 */
	public static Role from(String claim) {
		for (Role role : values()) {
			if (role.name().equals(claim)) {
				return role;
			}
		}
		return USER;
	}
}
