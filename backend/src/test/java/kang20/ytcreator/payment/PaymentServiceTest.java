package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.entity.Order;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
class PaymentServiceTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";
	private static final OrderId ORDER = new OrderId(RAW);
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);

	private static final String ONE_TIME_SKU = "test.one-time";
	private static final String SUBSCRIPTION_SKU = "test.subscription";

	@Autowired
	private PaymentPurchasePort paymentPurchase;

	@Autowired
	private OrderRepository orderRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void 원장을_비운다() {
		orderRepository.deleteAll();
	}

	@Test
	@DisplayName("결제된 주문은 원장에 남고 상품 유형을 돌려준다")
	void 지급() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		GrantResult result = paymentPurchase.grant(OWNER, ORDER);

		assertThat(result.granted()).isTrue();
		assertThat(result.productType()).isEqualTo(ProductType.CONSUMABLE);

		Order saved = orderRepository.findByOrderId(ORDER).orElseThrow();
		assertThat(saved.getUserId()).isEqualTo(OWNER);
		assertThat(saved.getSku()).isEqualTo(ONE_TIME_SKU);
	}

	@Test
	@DisplayName("구독 주문도 같은 원장에 남는다")
	void 구독_지급() {
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);

		assertThat(paymentPurchase.grant(OWNER, ORDER).productType()).isEqualTo(ProductType.SUBSCRIPTION);
	}

	@Test
	@DisplayName("PAYMENT_COMPLETED 도 지급 대상이다")
	void 지급_대상_판정() {
		토스가_답한다(OrderStatus.PAYMENT_COMPLETED, ONE_TIME_SKU);

		assertThat(paymentPurchase.grant(OWNER, ORDER).granted()).isTrue();
	}

	@Test
	@DisplayName("같은 주문을 다시 요청해도 성공이고, 원장은 한 번만 늘어난다")
	void 멱등() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		GrantResult first = paymentPurchase.grant(OWNER, ORDER);
		GrantResult again = paymentPurchase.grant(OWNER, ORDER);

		assertThat(first.granted()).isTrue();
		assertThat(again.granted()).isTrue();
		assertThat(again.productType()).isEqualTo(first.productType());
		assertThat(orderRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("이미 지급된 주문은 토스를 부르지 않는다")
	void 재요청은_토스를_부르지_않는다() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(OWNER, ORDER);
		org.mockito.Mockito.clearInvocations(tossOrderClient);

		paymentPurchase.grant(OWNER, ORDER);

		verify(tossOrderClient, never()).statusOf(any());
	}

	@Test
	@DisplayName("남의 주문에 지급을 요청하면 거부된다 — 선점 위반")
	void 선점_위반() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(OWNER, ORDER);

		assertThatThrownBy(() -> paymentPurchase.grant(OTHER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_005);

		assertThat(orderRepository.findByOrderId(ORDER).orElseThrow().getUserId())
			.isEqualTo(OWNER);
	}

	@Test
	@DisplayName("거부 메시지에 주문 식별자 원문이 실리지 않는다")
	void 주문_식별자_비노출() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(OWNER, ORDER);

		assertThatThrownBy(() -> paymentPurchase.grant(OTHER, ORDER))
			.hasMessageNotContaining(RAW);
	}

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({
		"ORDER_IN_PROGRESS, PAY_002",
		"FAILED,            PAY_003",
		"REFUNDED,          PAY_003",
		"NOT_FOUND,         PAY_004",
		"MINIAPP_MISMATCH,  PAY_004",
		"ERROR,             PAY_006"
	})
	@DisplayName("지급할 수 없는 주문은 상태별 코드로 거부한다")
	void 지급_불가(OrderStatus status, ErrorCode expected) {
		토스가_답한다(status, ONE_TIME_SKU);

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", expected);

		assertThat(orderRepository.count()).isZero();
	}

	@Test
	@DisplayName("주문을 확인하지 못하면 지급하지 않는다")
	void 검증_실패() {
		when(tossOrderClient.statusOf(any())).thenReturn(TossOrderStatus.unavailable());

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_006);

		assertThat(orderRepository.count()).isZero();
	}

	@Test
	@DisplayName("모르는 상품 코드는 지급하지 않는다")
	void 남의_상품() {
		토스가_답한다(OrderStatus.PURCHASED, "someone.else.product");

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_004);

		assertThat(orderRepository.count()).isZero();
	}

	@Test
	@DisplayName("상품 코드가 없으면 지급하지 않는다")
	void 상품_코드_없음() {
		when(tossOrderClient.statusOf(any()))
			.thenReturn(TossOrderStatus.of(OrderStatus.PURCHASED.name(), null));

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_004);
	}

	@Test
	@DisplayName("빈 주문 식별자로는 인자 자체를 만들 수 없다 — 토스는 부를 일이 없다")
	void 빈_주문_식별자() {
		assertThatThrownBy(() -> new OrderId("  "))
			.isInstanceOf(IllegalArgumentException.class);

		verify(tossOrderClient, never()).statusOf(any());
	}

	private void 토스가_답한다(OrderStatus status, String sku) {
		when(tossOrderClient.statusOf(any())).thenReturn(TossOrderStatus.of(status.name(), sku));
	}
}
