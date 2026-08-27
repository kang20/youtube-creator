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

/**
 * refresh 토큰 — <b>원문을 저장하지 않는다</b>(U10). 행에 남는 것은 SHA-256 hex 64자뿐이다.
 * 스키마: auth-design.md §14-2 · deploy/sql/auth-v2.sql.
 *
 * <p>{@code id} 는 원시 {@code Long} 이다 — 밖으로 나가지 않는 내부 대리키는 타입화하지 않는다
 * (architecture.md "타입화된 기본키"). {@code userId} 는 노출 식별자라 {@link UserId} 값 컬럼이다.
 *
 * <p>{@code revokedAt != null} 이면 회전·재사용 폐기로 죽은 토큰이다. 죽은 행을 지우지 않는 이유:
 * <b>재사용 감지(U9)의 근거가 바로 이 행이다</b> — 지우면 재사용이 "미존재"와 구분되지 않는다.
 */
@Entity
@Table(
	name = "refresh_tokens",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_refresh_tokens_token_hash", columnNames = "token_hash")
)
public class RefreshToken extends BaseTimeEntity {

	/** SHA-256 hex 의 고정 길이 — users.anonymous_key_hash 와 같은 규율(§3-2). */
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

	/** null = 활성. 회전·전체 폐기는 조건부 UPDATE(JPQL)로만 채운다 — 세터가 없다. */
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

	/** 이미 회전·폐기된 토큰인가 — true 면 재제출은 재사용(U9 탈취 신호)이다. */
	public boolean isRevoked() {
		return revokedAt != null;
	}

	/** 만료 판정 — {@code expiresAt <= now}. 경계 시각 정확히 그 순간부터 무효다. */
	public boolean isExpired(LocalDateTime now) {
		return !expiresAt.isAfter(now);
	}
}
