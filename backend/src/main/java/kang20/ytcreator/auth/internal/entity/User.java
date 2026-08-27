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

	/** SHA-256 hex 의 고정 길이. 입력 상한({@code AnonymousKeyFormat.MAX_LENGTH})과 다른 축이다(§12-2). */
	private static final int ANONYMOUS_KEY_HASH_LENGTH = 64;

	/**
	 * <b>타입화된 기본키</b>(2026-08-11 사용자 결정 — youngZZ {@code AnonymousUser} 선례).
	 * IDENTITY 채번값(Long)을 {@link UserIdJavaType#wrap} 이 {@link UserId} 로 감싸 주입한다 —
	 * 별도 IdentifierGenerator 불요. {@code users} DDL 은 그대로 {@code BIGINT AUTO_INCREMENT} 다.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@JavaType(UserIdJavaType.class)
	@Column(name = "id")
	private UserId id;

	/** {@code Role} 이름의 최대 길이 여유분. 값 자체가 짧아 넉넉히 잡아도 비용이 없다. */
	private static final int ROLE_LENGTH = 20;

	@Column(name = "anonymous_key_hash", nullable = false, length = ANONYMOUS_KEY_HASH_LENGTH)
	private String anonymousKeyHash;

	/**
	 * 권한 — <b>ORDINAL 이 아니라 STRING</b> 이다. ordinal 은 enum 상수 순서를 바꾸는 순간
	 * 기존 행의 의미가 조용히 뒤바뀐다(USER 가 ADMIN 이 된다).
	 *
	 * <p>승격 경로가 코드에 없는 것은 의도다 — 운영자 부여는 DB 직접 변경으로만 한다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = ROLE_LENGTH)
	private Role role;

	protected User() {
	}

	/** 신규 등록 — 언제나 {@link Role#USER} 에서 시작한다. */
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
