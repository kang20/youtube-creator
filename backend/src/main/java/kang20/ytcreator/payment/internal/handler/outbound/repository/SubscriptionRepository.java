package kang20.ytcreator.payment.internal.handler.outbound.repository;

import kang20.ytcreator.payment.internal.entity.Subscription;

import java.util.Optional;
import kang20.ytcreator.auth.UserId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 기간권 저장소. 웹훅은 {@code orderId} 로(§5-4), 이용권 조회는 {@code userId} 로(§5-3) 찾는다 —
 * 둘 다 UNIQUE 라 결과는 항상 0..1 행이다(§3).
 */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	Optional<Subscription> findByUserId(UserId userId);

	Optional<Subscription> findByOrderId(String orderId);
}
