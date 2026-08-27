package kang20.ytcreator.credit.internal.entity;

import static java.util.Objects.requireNonNull;
import static lombok.AccessLevel.PROTECTED;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.shared.domain.AggregateRootEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JavaType;

@Entity
@Table(
	name = "credit_balance",
	uniqueConstraints = @UniqueConstraint(name = "uk_credit_balance_user_id", columnNames = "user_id")
)
@Getter
@NoArgsConstructor(access = PROTECTED)
public class CreditBalance extends AggregateRootEntity<CreditBalance> {

	/** 밖으로 나가지 않는 대리키 — 원시 {@code Long} 유지(architecture.md "타입화된 식별자"). */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Embedded
	@AttributeOverride(name = "value", column = @Column(name = "balance", nullable = false))
	private Balance balance;

	private CreditBalance(UserId userId) {
		this.userId = requireNonNull(userId);
		this.balance = new Balance(1L);
	}

	public static CreditBalance create(UserId userId) {
		return new CreditBalance(userId);
	}
}
