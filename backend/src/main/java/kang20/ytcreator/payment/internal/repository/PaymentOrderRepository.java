package kang20.ytcreator.payment.internal.repository;

import kang20.ytcreator.payment.internal.entity.PaymentOrder;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 원장 저장소. {@code findByOrderId} 가 멱등(U3)·선점(U4)·경쟁 패자 재조회(§6-5 ④)의
 * 진입점이다 — 트랜잭션 밖 호출이라 호출마다 새 스냅샷을 본다(§6-3).
 */
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

	Optional<PaymentOrder> findByOrderId(String orderId);
}
