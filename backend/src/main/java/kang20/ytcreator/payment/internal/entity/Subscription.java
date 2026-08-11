package kang20.ytcreator.payment.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 기간권 — 구독 주문당 1행(payment-design.md §3).
 *
 * <p>⚠️ {@code UNIQUE(user_id)} — 한 사용자에게 활성 구독은 하나다. 플랜이 하나뿐이라
 * 두 번째 활성 구독은 정상 상태가 아니며, DB 가 거부하고 {@code PAY_003} 으로 떨어진다(§3).
 *
 * <p>⚠️ {@code expiresAtEstimated} 가 없으면 STALE 판정이 거짓말한다 — 추정값(+31일)과
 * 웹훅이 준 정본을 구분하지 못하면, 웹훅을 <b>한 번도 못 받은 구독</b>과 <b>받았지만 만료된
 * 구독</b>이 같아 보인다(§3).
 */
@Entity
@Table(
	name = "subscriptions",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_subscriptions_order_id", columnNames = "order_id"),
		@UniqueConstraint(name = "uk_subscriptions_user_id", columnNames = "user_id")
	}
)
public class Subscription extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 최초 구독 주문 ID — 웹훅이 이 값으로 찾아온다(§5-4). U14 비노출. */
	@Column(name = "order_id", nullable = false, length = 64)
	private String orderId;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SubscriptionStatus status;

	/** 만료 예정. 최초엔 추정값(결제일+31일 — §4-7-1④)이고 웹훅 정본이 덮는다. */
	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "auto_renew", nullable = false)
	private boolean autoRenew;

	/**
	 * 순서 역전 판정용(C5). null = 웹훅 미수신.
	 * ⚠️ recheck 는 이 값을 갱신하지 않는다(§4-7-1⑦) — 웹훅이 정본으로 남는 급소다(§5-5).
	 */
	@Column(name = "last_webhook_occurred_at")
	private LocalDateTime lastWebhookOccurredAt;

	/** true = 웹훅이 아직 한 번도 덮지 않았다(§3). */
	@Column(name = "expires_at_estimated", nullable = false)
	private boolean expiresAtEstimated;

	protected Subscription() {
	}

	/** 최초 지급(§6-5) — ACTIVE·추정 만료·웹훅 미수신 상태로 태어난다. */
	public Subscription(String orderId, UserId userId, LocalDateTime estimatedExpiresAt) {
		this.orderId = orderId;
		this.userId = userId;
		this.status = SubscriptionStatus.ACTIVE;
		this.expiresAt = estimatedExpiresAt;
		this.autoRenew = true;
		this.expiresAtEstimated = true;
	}

	public Long getId() {
		return id;
	}

	public String getOrderId() {
		return orderId;
	}

	public UserId getUserId() {
		return userId;
	}

	public SubscriptionStatus getStatus() {
		return status;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public boolean isAutoRenew() {
		return autoRenew;
	}

	public LocalDateTime getLastWebhookOccurredAt() {
		return lastWebhookOccurredAt;
	}

	public boolean isExpiresAtEstimated() {
		return expiresAtEstimated;
	}

	/** C5 순서 역전·중복 판정(§5-4③). null(미수신)이면 버리지 않는다. */
	public boolean alreadySeen(LocalDateTime occurredAt) {
		return lastWebhookOccurredAt != null && !occurredAt.isAfter(lastWebhookOccurredAt);
	}

	/**
	 * 웹훅 반영(§5-4④) — 정본이 왔다({@code expiresAtEstimated=false}).
	 *
	 * <p>{@code expiresAt}/{@code occurredAt} 이 null 이면 기존 값을 유지한다 —
	 * 플랫폼이 null 을 줄 수 있고(iap-essentials §3-6), 정본을 무로 덮으면 만료 판정이 통째로 죽는다.
	 */
	public void applyWebhook(SubscriptionStatus status, LocalDateTime expiresAt, boolean autoRenew,
			LocalDateTime occurredAt) {
		this.status = status;
		if (expiresAt != null) {
			this.expiresAt = expiresAt;
		}
		this.autoRenew = autoRenew;
		this.expiresAtEstimated = false;
		if (occurredAt != null) {
			this.lastWebhookOccurredAt = occurredAt;
		}
	}

	/**
	 * recheck 반영(§5-5) — 클라 스냅샷은 임시 보정이다.
	 * ⚠️ {@code lastWebhookOccurredAt} 을 건드리지 않는다(§4-7-1⑦) — 갱신하면 뒤늦게 도착한
	 * 웹훅이 "과거 이벤트"로 버려져 클라가 보낸 값이 영구히 정본이 된다.
	 */
	public void applyClientSnapshot(SubscriptionStatus status, LocalDateTime expiresAt, boolean autoRenew) {
		this.status = status;
		if (expiresAt != null) {
			this.expiresAt = expiresAt;
		}
		this.autoRenew = autoRenew;
		this.expiresAtEstimated = false;
	}
}
