package kang20.ytcreator.payment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import org.hibernate.annotations.JavaType;

/**
 * 주문 원장 — 멱등(U3)·선점(U4)의 <b>유일한</b> 근거(payment-design.md §3).
 * {@code createdAt} 이 곧 지급 시각이라 별도 컬럼을 만들지 않는다.
 *
 * <p>🔴 {@code UNIQUE(order_id)} 가 멱등의 근거다 — 동시 지급 경쟁(C1)을 DB 가 최종 판정한다(§6-3).
 *
 * <p>PK 는 surrogate 원시 {@code Long} 이다 — 밖으로 나가지 않는 식별자는 타입화하지 않는다(§3).
 * {@code userId} 는 연관관계가 아니라 {@code @JavaType} 값 컬럼이다(architecture.md "타입화된 기본키").
 */
@Entity
@Table(
	name = "payment_orders",
	uniqueConstraints = @UniqueConstraint(name = "uk_payment_orders_order_id", columnNames = "order_id"),
	indexes = @Index(name = "ix_payment_orders_user_id", columnList = "user_id")
)
public class PaymentOrder extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 토스 주문 ID(uuid v7 — ✅-12). ⚠️ 응답·예외 메시지에 싣지 않는다(U14). */
	@Column(name = "order_id", nullable = false, length = 64)
	private String orderId;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	/** 토스 응답의 sku — 클라 주장이 아니다(payment.md §5-4). */
	@Column(name = "sku", nullable = false, length = 128)
	private String sku;

	@Enumerated(EnumType.STRING)
	@Column(name = "product_type", nullable = false, length = 16)
	private ProductType productType;

	protected PaymentOrder() {
	}

	public PaymentOrder(String orderId, UserId userId, String sku, ProductType productType) {
		this.orderId = orderId;
		this.userId = userId;
		this.sku = sku;
		this.productType = productType;
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

	public String getSku() {
		return sku;
	}

	public ProductType getProductType() {
		return productType;
	}

	/** U3(멱등 200) ↔ U4(선점 PAY_005) 판정(§5-2①·④). */
	public boolean ownedBy(UserId userId) {
		return this.userId.equals(userId);
	}
}
