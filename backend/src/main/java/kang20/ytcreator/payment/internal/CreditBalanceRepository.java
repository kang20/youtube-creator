package kang20.ytcreator.payment.internal;

import java.util.Optional;
import kang20.ytcreator.auth.UserId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 횟수권 잔량 저장소 — 증감은 전부 <b>조건부 UPDATE 한 방</b>이다(payment-design.md §6-4).
 *
 * <p>JPQL 이라 H2·MySQL 이 동일하게 동작하고, 타입 ID 파라미터는 자동 언랩된다(§6-4).
 * ⚠️ {@code clearAutomatically} 를 켠다 — 안 켜면 같은 트랜잭션의 영속성 컨텍스트가
 * 옛 balance 를 들고 있어 직후 조회가 차감 전 값을 준다.
 */
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

	Optional<CreditBalance> findByUserId(UserId userId);

	/**
	 * C3(잔량 차감) — 영향 행 수가 곧 판정이다: 1=성공, 0=부족(→ PAY_001).
	 * 불변식 2(음수 금지)를 DB 가 원자적으로 지킨다 — 읽은 값을 신뢰하지 않는다(§6-4).
	 */
	@Modifying(clearAutomatically = true)
	@Query("update CreditBalance c set c.balance = c.balance - 1 where c.userId = :userId and c.balance > 0")
	int decrementIfPositive(@Param("userId") UserId userId);

	/**
	 * 잔량 +1. C2 증가 1단계(행 있으면 끝 — 0행이면 호출자가 insert 로 넘어간다 §6-4)와
	 * {@code release} 의 되돌림(§5-6)이 함께 쓴다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update CreditBalance c set c.balance = c.balance + 1 where c.userId = :userId")
	int increment(@Param("userId") UserId userId);
}
