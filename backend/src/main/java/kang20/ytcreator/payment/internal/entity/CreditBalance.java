package kang20.ytcreator.payment.internal.entity;

import kang20.ytcreator.payment.internal.handler.outbound.repository.CreditBalanceRepository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.auth.UserIdJavaType;
import kang20.ytcreator.shared.domain.BaseTimeEntity;
import org.hibernate.annotations.JavaType;

/**
 * 횟수권 잔량 — 사용자당 1행(payment-design.md §3).
 *
 * <p>⚠️ <b>잔량 변경 세터가 없는 것이 설계다.</b> 읽은 값을 신뢰하는 "읽고→빼고→저장"은
 * C3 을 통과시킨다(§6-2 함정 ⑤). 증감은 전부 {@link CreditBalanceRepository} 의
 * 조건부 UPDATE 로만 한다(§6-4). 음수 방지도 CHECK 가 아니라 그 UPDATE 하나가 지킨다(§3-1).
 */
@Entity
@Table(
	name = "credit_balances",
	uniqueConstraints = @UniqueConstraint(name = "uk_credit_balances_user_id", columnNames = "user_id")
)
public class CreditBalance extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@JavaType(UserIdJavaType.class)
	@Column(name = "user_id", nullable = false, updatable = false)
	private UserId userId;

	@Column(name = "balance", nullable = false)
	private int balance;

	protected CreditBalance() {
	}

	public CreditBalance(UserId userId, int balance) {
		this.userId = userId;
		this.balance = balance;
	}

	public Long getId() {
		return id;
	}

	public UserId getUserId() {
		return userId;
	}

	public int getBalance() {
		return balance;
	}
}
