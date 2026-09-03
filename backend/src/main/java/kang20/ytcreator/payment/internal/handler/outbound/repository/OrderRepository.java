package kang20.ytcreator.payment.internal.handler.outbound.repository;

import java.util.Optional;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.OrderId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

	Optional<Order> findByOrderId(OrderId orderId);
}
