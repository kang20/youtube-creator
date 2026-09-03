package kang20.ytcreator.subscription.internal.handler.outbound.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	Optional<Subscription> findByOrderId(OrderId orderId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.orderId = :orderId")
	Optional<Subscription> findByOrderIdForUpdate(@Param("orderId") OrderId orderId);
}
