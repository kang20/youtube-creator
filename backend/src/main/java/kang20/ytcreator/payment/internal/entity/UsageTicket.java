package kang20.ytcreator.payment.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.payment.UsageTicketId;
import kang20.ytcreator.payment.UsageTicketIdJavaType;
import kang20.ytcreator.payment.dto.TicketSource;
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import org.hibernate.annotations.JavaType;

/**
 * 소모 예약 — ✅-4ⓐ "생성 시 예약 → 완료 확정"의 실체(payment-design.md §3).
 *
 * <p>⚠️ PK 만 타입화한다({@link UsageTicketId}) — subtitle 에 노출되는 유일한 payment 식별자다.
 * 상태 전이는 엔티티 세터가 아니라 조건부 UPDATE 로만 한다(§6-4 C4 — 중복 release 가
 * +1 을 두 번 하면 무료 이용권이 샌다).
 *
 * <p>정리 배치를 만들지 않는다 — 소모 이력은 U8 분쟁 대응에 쓸모가 있다(§6-6).
 */
@Entity
@Table(
	name = "usage_tickets",
	indexes = @Index(name = "ix_usage_tickets_user_id_status", columnList = "user_id, status")
)
public class UsageTicket extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@JavaType(UsageTicketIdJavaType.class)
	@Column(name = "id")
	private UsageTicketId id;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 16)
	private TicketSource source;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private TicketStatus status;

	protected UsageTicket() {
	}

	/** 예약 상태(RESERVED)로 태어난다(§5-6). */
	public UsageTicket(UserId userId, TicketSource source) {
		this.userId = userId;
		this.source = source;
		this.status = TicketStatus.RESERVED;
	}

	public UsageTicketId getId() {
		return id;
	}

	public UserId getUserId() {
		return userId;
	}

	public TicketSource getSource() {
		return source;
	}

	public TicketStatus getStatus() {
		return status;
	}
}
