package kang20.ytcreator.subscription.internal.entity;

import static java.util.Objects.requireNonNull;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.OrderIdConverter;
import kang20.ytcreator.shared.domain.AggregateRootEntity;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JavaType;
import org.hibernate.annotations.NaturalId;

@Entity
@Table(
	name = "subscriptions",
	uniqueConstraints = @UniqueConstraint(name = Subscription.UK_ORDER_ID, columnNames = "order_id"),
	indexes = @Index(name = "ix_subscriptions_user_id", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Subscription extends AggregateRootEntity<Subscription> {

	public static final String UK_ORDER_ID = "uk_subscriptions_order_id";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Convert(converter = OrderIdConverter.class)
	@NaturalId
	@Column(name = "order_id", nullable = false, updatable = false, length = 64)
	private OrderId orderId;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SubscriptionStatus status;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "auto_renew", nullable = false)
	private boolean autoRenew;

	@Column(name = "last_webhook_occurred_at")
	private LocalDateTime lastWebhookOccurredAt;

	private Subscription(OrderId orderId, UserId userId, LocalDateTime estimatedExpiresAt) {
		this.orderId = requireNonNull(orderId);
		this.userId = requireNonNull(userId);
		this.status = SubscriptionStatus.ACTIVE;
		this.expiresAt = requireNonNull(estimatedExpiresAt);
		this.autoRenew = true;
	}

	public static Subscription start(OrderId orderId, UserId userId, LocalDateTime estimatedExpiresAt) {
		return new Subscription(orderId, userId, estimatedExpiresAt);
	}

	public void applyClientSnapshot(SubscriptionStatus status, LocalDateTime expiresAt, boolean autoRenew) {
		this.status = requireNonNull(status);
		if (expiresAt != null) {
			this.expiresAt = expiresAt;
		}
		this.autoRenew = autoRenew;
	}

	public boolean applyWebhook(SubscriptionStatus status, WebhookEvent.Snapshot current,
			LocalDateTime occurredAt) {
		if (isStale(occurredAt)) {
			return false;
		}

		this.status = requireNonNull(status);
		if (current.expiresAt() != null) {
			this.expiresAt = current.expiresAt();
		}
		if (current.autoRenew() != null) {
			this.autoRenew = current.autoRenew();
		}
		if (occurredAt != null) {
			this.lastWebhookOccurredAt = occurredAt;
		}
		return true;
	}

	public boolean hasMissedWebhook(String previousStatus) {
		return previousStatus != null && !status.name().equals(previousStatus);
	}

	// 이전에 발생한 변경 요청이면 false 를 뱉는다
	private boolean isStale(LocalDateTime occurredAt) {
		return occurredAt != null && lastWebhookOccurredAt != null
			&& !lastWebhookOccurredAt.isBefore(occurredAt);
	}
}
