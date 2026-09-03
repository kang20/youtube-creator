package kang20.ytcreator.auth;

public enum Role {

	USER,

	ADMIN;

	public String authority() {
		return "ROLE_" + name();
	}

	public static Role from(String claim) {
		for (Role role : values()) {
			if (role.name().equals(claim)) {
				return role;
			}
		}
		return USER;
	}
}
