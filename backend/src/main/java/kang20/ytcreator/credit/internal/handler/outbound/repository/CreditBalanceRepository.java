package kang20.ytcreator.credit.internal.handler.outbound.repository;

import java.time.LocalDateTime;

import kang20.ytcreator.credit.internal.entity.CreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

	@Modifying(clearAutomatically = true)
	@Query(value = "update credit_balance set balance = balance + 1, updated_at = :now where user_id = :userId",
		nativeQuery = true)
	int increment(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
