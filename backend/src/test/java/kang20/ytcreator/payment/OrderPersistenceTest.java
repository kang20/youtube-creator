package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
class OrderPersistenceTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);
	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	@Autowired
	private OrderRepository orderRepository;

	@BeforeEach
	void 원장을_비운다() {
		orderRepository.deleteAll();
	}

	@Test
	@DisplayName("주문 식별자로 저장하고 같은 값으로 찾는다 — 값 객체가 컬럼을 오간다")
	void 저장과_조회() {
		OrderId orderId = new OrderId(RAW);
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(orderId, SKU, ProductType.CONSUMABLE), OWNER));

		Order found = orderRepository.findByOrderId(new OrderId(RAW)).orElseThrow();

		assertThat(found.getOrderId()).isEqualTo(orderId);
		assertThat(found.getOrderId().raw()).isEqualTo(RAW);
		assertThat(found.getUserId()).isEqualTo(OWNER);
		assertThat(found.getSku()).isEqualTo(SKU);
		assertThat(found.getProductType()).isEqualTo(ProductType.CONSUMABLE);
	}

	@Test
	@DisplayName("같은 주문 식별자는 두 번 저장되지 않는다 — UNIQUE 가 멱등의 근거다")
	void 주문_식별자는_유일하다() {
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OWNER));

		assertThatThrownBy(() -> orderRepository
			.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OWNER)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("소유자가 달라도 같은 주문 식별자는 거부된다")
	void 다른_소유자의_같은_주문도_거부된다() {
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OWNER));

		assertThatThrownBy(() -> orderRepository
			.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OTHER)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("다른 주문 식별자는 같은 사용자여도 각각 남는다 — 재구매는 새 주문이다")
	void 재구매는_새_주문이다() {
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OWNER));
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(new OrderId("other-order"), SKU, ProductType.CONSUMABLE), OWNER));

		assertThat(orderRepository.findByOrderId(new OrderId(RAW))).isPresent();
		assertThat(orderRepository.findByOrderId(new OrderId("other-order"))).isPresent();
	}

	@Test
	@DisplayName("지급 일시는 생성 일시다 — 별도 컬럼을 두지 않는다")
	void 지급_일시() {
		orderRepository.saveAndFlush(Order.grant(new GrantRequest(new OrderId(RAW), SKU, ProductType.CONSUMABLE), OWNER));

		Order found = orderRepository.findByOrderId(new OrderId(RAW)).orElseThrow();

		assertThat(found.getGrantedAt()).isNotNull().isEqualTo(found.getCreatedAt());
	}

	@Test
	@DisplayName("모르는 주문 식별자는 빈 결과다 — 미지급 주문의 선판정 경로")
	void 없는_주문() {
		assertThat(orderRepository.findByOrderId(new OrderId("unknown-order"))).isEmpty();
	}
}
