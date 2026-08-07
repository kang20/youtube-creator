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
 * 익명키당 정확히 하나 존재하는 사용자(멱등).
 *
 * <p>⚠️ <b>익명키 원문을 저장하지 않는다.</b> {@link AnonymousKeyHasher} 가 만든
 * SHA-256 hex 64자만 갖는다(docs/domain/auth-design.md §3-2, blockers B4).
 * 원문을 넣으면 UNIQUE 위반 메시지에 그 값이 실려 로그로 새고 U6 를 어긴다.
 *
 * <p>등록 시각을 위한 별도 컬럼을 두지 않는다 — 등록 시각 = 행 생성 시각이므로
 * {@link BaseTimeEntity#getCreatedAt()} 이 그대로 {@code registeredAt} 이다.
 *
 * <p>PK 는 surrogate 다. 익명키(해시)를 PK 로 삼으면 다른 모듈이 FK 로 그 값을 들고 다니게 되고,
 * 미니앱 재출시로 익명키가 바뀔 때 PK 를 갈아야 한다(§3).
 *
 * <p>{@code anonymous_key_hash} 의 UNIQUE 제약이 멱등의 <b>유일한</b> 근거다 — 동시 등록 경쟁은
 * DB 가 최종 판정한다(§6).
 */
@Entity
@Table(
	name = "users",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_users_anonymous_key_hash", columnNames = "anonymous_key_hash")
)
public class User extends BaseTimeEntity {

	/**
	 * SHA-256 hex 의 고정 길이다. <b>잠정값이 아니다</b> — 해시 저장 결정으로 §12-2(길이 잠정)는 해소됐다.
	 *
	 * <p>입력 검증 상한({@code AnonymousKeyFormat.MAX_LENGTH})과는 <b>다른 값이고 다른 축</b>이다.
	 * 저장은 해시(64), 검증은 들어오는 원문 — 둘은 같이 움직이지 않는다(§12-2 v2).
	 */
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
