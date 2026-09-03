package kang20.ytcreator.auth.internal.entity;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import org.hibernate.annotations.JavaType;

@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_users_anonymous_key_hash", columnNames = "anonymous_key_hash")
)
public class User extends BaseTimeEntity {

	private static final int ANONYMOUS_KEY_HASH_LENGTH = 64;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@JavaType(UserIdJavaType.class)
	@Column(name = "id")
	private UserId id;

	private static final int ROLE_LENGTH = 20;

	@Column(name = "anonymous_key_hash", nullable = false, length = ANONYMOUS_KEY_HASH_LENGTH)
	private String anonymousKeyHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = ROLE_LENGTH)
	private Role role;

	protected User() {
	}

	public User(String anonymousKeyHash) {
		this.anonymousKeyHash = requireNonNull(anonymousKeyHash);
		this.role = Role.USER;
	}

	public UserId getId() {
		return id;
	}

	public String getAnonymousKeyHash() {
		return anonymousKeyHash;
	}

	public Role getRole() {
		return role;
	}
}
