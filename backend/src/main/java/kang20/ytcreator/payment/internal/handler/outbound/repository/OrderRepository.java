package kang20.ytcreator.payment.internal.handler.outbound.repository;

import java.util.Optional;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.OrderId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 원장 저장소.
 *
 * <p>🔴 <b>멱등의 근거는 이 인터페이스가 아니라 {@code UNIQUE(order_id)} 제약이다.</b>
 * {@link #findByOrderId} 는 <b>선판정</b>일 뿐이다 — 이미 지급된 주문이면 토스를 부르지 않고
 * 바로 답하기 위한 것이고, 동시 요청을 막지는 못한다. 두 요청이 같은 순간 빈 결과를 볼 수 있다.
 *
 * <p>그래서 지급 경로는 <b>{@code saveAndFlush} 로 삽입을 시도</b>하고,
 * {@code DataIntegrityViolationException}(UNIQUE 위반)을 <b>경쟁에서 졌다는 정상 판정</b>으로 읽는다.
 * ⚠️ 그 예외를 같은 트랜잭션 안에서 잡아 살리려 하지 마라 — rollback-only 라 못 살린다.
 * 경쟁 판정은 트랜잭션 밖에서 한다.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

	/** 멱등·선점 선판정용. 결과가 있으면 {@code Order.ownedBy} 로 재요청과 선점 위반을 가른다. */
	Optional<Order> findByOrderId(OrderId orderId);
}
