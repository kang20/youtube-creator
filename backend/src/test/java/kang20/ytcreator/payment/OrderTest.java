package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.internal.entity.dto.GrantRequest;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.ProductType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 주문 애그리거트 루트의 도메인 규칙 (new-domain/payment.md 주문 애그리거트).
 *
 * <p>저장소를 타지 않는 순수 단위다. UNIQUE 제약·소유자 불변은 {@link OrderPersistenceTest} 가 본다.
 */
class OrderTest {

	private static final OrderId ORDER_ID = new OrderId("13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0");
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);
	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	@Test
	@DisplayName("지급은 검증된 주문 정보로 원장 한 행을 만든다")
	void 지급() {
		Order order = Order.grant(new GrantRequest(ORDER_ID, SKU, ProductType.CONSUMABLE), OWNER);

		assertThat(order.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(order.getUserId()).isEqualTo(OWNER);
		assertThat(order.getSku()).isEqualTo(SKU);
		assertThat(order.getProductType()).isEqualTo(ProductType.CONSUMABLE);
	}

	/** 재요청은 오류가 아니다 — 소유자가 같으면 멱등 히트이고 성공으로 답한다. */
	@Test
	@DisplayName("소유자가 같으면 재요청이다")
	void 소유_판정_같은_사용자() {
		Order order = Order.grant(new GrantRequest(ORDER_ID, SKU, ProductType.CONSUMABLE), OWNER);

		assertThat(order.ownedBy(OWNER)).isTrue();
	}

	/** 🔴 선점 위반. 같은 주문이라도 다른 사용자면 멱등 히트가 아니라 거부다. */
	@Test
	@DisplayName("소유자가 다르면 선점 위반이다 — 남의 주문은 가로챌 수 없다")
	void 소유_판정_다른_사용자() {
		Order order = Order.grant(new GrantRequest(ORDER_ID, SKU, ProductType.CONSUMABLE), OWNER);

		assertThat(order.ownedBy(OTHER)).isFalse();
	}

	@Test
	@DisplayName("구독 주문도 같은 원장에 남는다 — 상품 유형만 다르다")
	void 구독_주문() {
		Order order = Order.grant(new GrantRequest(ORDER_ID, SKU, ProductType.SUBSCRIPTION), OWNER);

		assertThat(order.getProductType()).isEqualTo(ProductType.SUBSCRIPTION);
	}

	/**
	 * 원장은 지급 이후 변하지 않는다 — 상태 필드도, 변경 행위도 없다.
	 * 세터가 생기면 여기서 먼저 빨개진다.
	 */
	@Test
	@DisplayName("주문에는 상태를 바꾸는 공개 행위가 없다")
	void 변경_행위가_없다() {
		assertThat(Order.class.getDeclaredMethods())
			.filteredOn(method -> method.getName().startsWith("set"))
			.isEmpty();
	}
}
