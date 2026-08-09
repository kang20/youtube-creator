package kang20.ytcreator.auth.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kang20.ytcreator.shared.domain.BaseTimeEntity;

/**
 * 익명키당 정확히 하나 존재하는 사용자(멱등). 설계: auth-design.md §3.
 *
 * <p>⚠️ <b>익명키 원문을 저장하지 않는다</b> — {@link AnonymousKeyHasher} 의 해시만 갖는다.
 * 원문을 넣으면 UNIQUE 위반 메시지에 실려 로그로 샌다(§3-2).
 *
 * <p>UNIQUE 제약이 멱등의 <b>유일한</b> 근거다 — 동시 등록 경쟁은 DB 가 최종 판정한다(§6).
 */
@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_users_anonymous_key_hash", columnNames = "anonymous_key_hash")
)
public class User extends BaseTimeEntity {

	/** SHA-256 hex 의 고정 길이. 입력 상한({@code AnonymousKeyFormat.MAX_LENGTH})과 다른 축이다(§12-2). */
	private static final int ANONYMOUS_KEY_HASH_LENGTH = 64;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "anonymous_key_hash", nullable = false, length = ANONYMOUS_KEY_HASH_LENGTH)
	private String anonymousKeyHash;

	protected User() {
	}

	public User(String anonymousKeyHash) {
		this.anonymousKeyHash = anonymousKeyHash;
	}

	public String getAnonymousKeyHash() {
		return anonymousKeyHash;
	}
}
