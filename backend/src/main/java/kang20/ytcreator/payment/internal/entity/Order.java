package kang20.ytcreator.payment.internal.entity;

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
import kang20.ytcreator.payment.ConsumableGranted;
import kang20.ytcreator.payment.SubscriptionGranted;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.OrderIdConverter;

import kang20.ytcreator.shared.domain.AggregateRootEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JavaType;
import org.hibernate.annotations.NaturalId;

@Entity
@Table(
	name = "orders",
	uniqueConstraints = @UniqueConstraint(name = "uk_orders_order_id", columnNames = "order_id"),
	indexes = @Index(name = "ix_orders_user_id", columnList = "user_id")
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class Order extends AggregateRootEntity<Order> {
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

	@Column(name = "sku", nullable = false, updatable = false, length = 128)
	private String sku;

	@Enumerated(EnumType.STRING)
	@Column(name = "product_type", nullable = false, updatable = false, length = 16)
	private ProductType productType;

	private Order(GrantRequest request, UserId userId) {
		this.orderId = request.orderId();
		this.userId = userId;
		this.sku = request.sku();
		this.productType = request.productType();
	}

	public static Order grant(GrantRequest request, UserId userId) {
		return new Order(request, userId).andEvent(
			request.productType() == ProductType.CONSUMABLE
				? new ConsumableGranted(userId, request.orderId())
				: new SubscriptionGranted(userId, request.orderId())
		);
	}

	public boolean ownedBy(UserId userId) {
		return this.userId.equals(userId);
	}

	public LocalDateTime getGrantedAt() {
		return getCreatedAt();
	}
}
