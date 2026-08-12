package kang20.ytcreator.auth.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

	@Column(name = "anonymous_key_hash", nullable = false, length = ANONYMOUS_KEY_HASH_LENGTH)
	private String anonymousKeyHash;

	protected User() {
	}

	public User(String anonymousKeyHash) {
		this.anonymousKeyHash = anonymousKeyHash;
	}

	public UserId getId() {
		return id;
	}

	public String getAnonymousKeyHash() {
		return anonymousKeyHash;
	}
}
