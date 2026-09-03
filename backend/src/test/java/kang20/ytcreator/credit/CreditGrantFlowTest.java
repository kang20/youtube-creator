package kang20.ytcreator.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.credit.internal.entity.Balance;
import kang20.ytcreator.credit.internal.entity.CreditBalance;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@ApplicationModuleTest(extraIncludes = "payment")
@Import({JpaAuditingConfig.class, CreditGrantFlowTest.TestClockConfig.class})
class CreditGrantFlowTest {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	private static final String ONE_TIME_SKU = "test.one-time";
	private static final String SUBSCRIPTION_SKU = "test.subscription";

	@Autowired
	private PaymentPurchasePort paymentPurchase;

	@Autowired
	private CreditBalanceRepository creditBalanceRepository;

	@Autowired
	private OrderRepository orderRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void 원장과_잔량을_비운다() {
		creditBalanceRepository.deleteAll();
		orderRepository.deleteAll();
	}

	@Test
	@DisplayName("단건 지급이 확정되면 그 사용자의 잔량 행이 1 로 생긴다")
	void 단건_지급_첫_흐름() {
		UserId user = new UserId(9101L);
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		paymentPurchase.grant(user, new OrderId("flow-cone-first"));

		assertThat(balanceOf(user).orElseThrow().getBalance())
			.as("동기 수신이므로 grant 가 돌아온 시점에 잔량이 확정돼 있어야 한다")
			.isEqualTo(new Balance(1L));
	}

	@Test
	@DisplayName("다른 주문으로 또 지급되면 잔량이 1 오른다")
	void 두_번째_주문_증가_흐름() {
		UserId user = new UserId(9102L);
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		paymentPurchase.grant(user, new OrderId("flow-ctwo-first"));
		assertThat(balanceOf(user).orElseThrow().getBalance()).isEqualTo(new Balance(1L));

		paymentPurchase.grant(user, new OrderId("flow-ctwo-second"));
		assertThat(balanceOf(user).orElseThrow().getBalance()).isEqualTo(new Balance(2L));
	}

	@Test
	@DisplayName("같은 주문의 재요청은 성공으로 답하되 이벤트를 다시 발행하지 않고 잔량도 그대로다")
	void 재요청_멱등() {
		UserId user = new UserId(9103L);
		OrderId order = new OrderId("flow-cthree-replay");
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		paymentPurchase.grant(user, order);
		assertThat(balanceOf(user).orElseThrow().getBalance()).isEqualTo(new Balance(1L));

		GrantResult replay = paymentPurchase.grant(user, order);

		assertThat(replay.granted()).isTrue();   // 재요청은 오류가 아니다 — 성공으로 답한다
		assertThat(balanceOf(user).orElseThrow().getBalance())
			.as("재요청이 이벤트를 또 발행하면 잔량이 또 올라 멱등이 깨진다")
			.isEqualTo(new Balance(1L));
	}

	@Test
	@DisplayName("구독 지급은 이벤트를 발행하지 않고 잔량 행도 생기지 않는다")
	void 구독_지급은_잔량_불변() {
		UserId user = new UserId(9104L);
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);

		GrantResult result = paymentPurchase.grant(user, new OrderId("flow-cfour-subscription"));

		assertThat(result.granted()).isTrue();
		assertThat(result.productType()).isEqualTo(ProductType.SUBSCRIPTION);
		assertThat(balanceOf(user)).isEmpty();
	}

	private void 토스가_답한다(OrderStatus status, String sku) {
		when(tossOrderClient.statusOf(any())).thenReturn(TossOrderStatus.of(status.name(), sku));
	}

	private Optional<CreditBalance> balanceOf(UserId userId) {
		return creditBalanceRepository.findAll().stream()
			.filter(row -> row.getUserId().equals(userId))
			.findFirst();
	}
}
