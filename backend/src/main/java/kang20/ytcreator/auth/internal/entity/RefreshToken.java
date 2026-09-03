package kang20.ytcreator.auth.internal.entity;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import org.hibernate.annotations.JavaType;

@Entity
@Table(
	name = "refresh_tokens",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_refresh_tokens_token_hash", columnNames = "token_hash")
)
public class RefreshToken extends BaseTimeEntity {

	private static final int TOKEN_HASH_LENGTH = 64;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Column(name = "token_hash", nullable = false, updatable = false, length = TOKEN_HASH_LENGTH)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	protected RefreshToken() {
	}

	public RefreshToken(UserId userId, String tokenHash, LocalDateTime expiresAt) {
		this.userId = requireNonNull(userId);
		this.tokenHash = requireNonNull(tokenHash);
		this.expiresAt = requireNonNull(expiresAt);
	}

	public Long getId() {
		return id;
	}

	public UserId getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public LocalDateTime getRevokedAt() {
		return revokedAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(LocalDateTime now) {
		return !expiresAt.isAfter(now);
	}
}
