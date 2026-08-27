package kang20.ytcreator.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.credit.internal.entity.Balance;
import kang20.ytcreator.credit.internal.entity.CreditBalance;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.ConsumableGranted;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.payment.SubscriptionGranted;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.OrderRepository;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;
import kang20.ytcreator.subscription.internal.handler.inbound.SubscriptionGrantedListener;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * S1·S2·S3·S4·S5 — payment → subscription 이벤트 결합
 * (payment.md "애그리거트 간 관계" — "주문과 구독은 최초 지급 시 함께 만들어져야 한다" ·
 * 사용자 확정 결정 "생성 결합은 이벤트").
 *
 * <p>subscription 슬라이스에 발행자 payment 와, S2 의 "횟수권만 오른다"를 확인할 credit 을
 * {@code extraIncludes} 로 얹는다 — 토스만 목으로 막고 DB 는 진짜를 쓴다.
 *
 * <p>🔴 <b>두 리스너 모두 동기 {@code @EventListener} 다</b> — 그래서 비동기 대기({@code Scenario})가
 * 필요 없고, {@code grant} 가 반환한 시점에 구독·잔량이 이미 확정돼 있다. 이 전제가 깨지면
 * (리스너가 트랜잭셔널 계열로 되돌아가면) S4 가 먼저 빨개진다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest(extraIncludes = {"payment", "credit"})
@Import({JpaAuditingConfig.class, SubscriptionGrantFlowTest.TestClockConfig.class})
class SubscriptionGrantFlowTest {

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	/** {@code TimeConfig} 직접 import 는 Modulith 빈 선별기를 죽인다(AuthServiceTest javadoc 실측). */
	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	/** {@code application-test.yml} 의 ytcreator.payment.*.sku 와 같아야 한다. */
	private static final String ONE_TIME_SKU = "test.one-time";
	private static final String SUBSCRIPTION_SKU = "test.subscription";

	@Autowired
	private PaymentPurchasePort paymentPurchase;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private CreditBalanceRepository creditBalanceRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** S3 의 "다시 발행하지 않았다"는 수신 횟수로만 증명된다 — 결과 행 수로는 못 가른다(S3 javadoc). */
	@MockitoSpyBean
	private SubscriptionGrantedListener subscriptionGrantedListener;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void 원장과_구독과_발행_기록을_비운다() {
		subscriptionRepository.deleteAll();
		creditBalanceRepository.deleteAll();
		orderRepository.deleteAll();
		jdbcTemplate.update("delete from event_publication");
	}

	// ── S1 — 구독 지급이 구독 계약을 연다 ───────────────────────────────

	/**
	 * S1 — SUBSCRIPTION 주문의 지급이 확정되면 {@code SubscriptionGranted} → 리스너 → 구독이
	 * <b>ACTIVE · 결제일+31일 추정 만료 · 웹훅 미수신</b>으로 생긴다.
	 *
	 * <p>"주문만 남으면 돈을 내고 못 쓴다" — 이 결합이 끊기면 결제한 사용자가 구독 없이 남는다.
	 */
	@Test
	@DisplayName("S1 — 구독 지급이 확정되면 ACTIVE·추정 만료·웹훅 미수신으로 구독이 생긴다")
	void 구독_지급_흐름() {
		UserId user = new UserId(9201L);
		OrderId order = new OrderId("flow-s1-subscription");
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);

		paymentPurchase.grant(user, order);

		Subscription subscription = subscriptionOf(user)
			.orElseThrow(() -> new AssertionError("동기 수신이라 grant 반환 시점에 구독이 있어야 한다"));
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getOrderId())
			.as("🔴 웹훅이 이 값으로 찾아온다 — 마스킹 값이 저장되면 이후 모든 웹훅이 버려진다")
			.isEqualTo(order);
		assertThat(subscription.getExpiresAt()).isEqualTo(FIXED_NOW.plusDays(31));
		assertThat(subscription.getLastWebhookOccurredAt()).isNull();
		assertThat(subscription.isAutoRenew()).isTrue();
	}

	// ── S2 — 단건 주문은 구독을 만들지 않는다 ───────────────────────────

	/**
	 * S2 — CONSUMABLE 지급은 <b>횟수권만</b> 올린다. 구독은 생기지 않는다
	 * (payment.md "CONSUMABLE: 지급하면 횟수권이 1 오른다" / "SUBSCRIPTION: 구독 계약이 생긴다").
	 */
	@Test
	@DisplayName("S2 — 단건 지급은 횟수권만 올리고 구독을 만들지 않는다")
	void 단건_지급은_구독을_만들지_않는다() {
		UserId user = new UserId(9202L);
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);

		paymentPurchase.grant(user, new OrderId("flow-s2-one-time"));

		assertThat(balanceOf(user).orElseThrow().getBalance()).isEqualTo(new Balance(1L));
		verify(subscriptionGrantedListener, never())
			.on(any(SubscriptionGranted.class));
		assertThat(subscriptionRepository.count())
			.as("단건에서 구독이 생기면 결제 없이 기간권이 열린다").isZero();
	}

	// ── S3 — 재요청(replay)은 다시 발행하지 않는다 ──────────────────────

	/**
	 * S3 — 같은 주문의 지급 재요청은 이벤트를 다시 발행하지 않는다 → 구독이 중복 생성되지 않는다
	 * (payment.md 멱등 규칙 · 확정 결정 "재요청(replay)은 발행하지 않는다").
	 *
	 * <p>🔴 <b>구독 행 수는 증거가 되지 못한다</b> — 재발행되더라도 리스너가 {@code SUB_001} 을 삼켜
	 * 행 수는 똑같이 1 로 남기 때문이다. 그래서 <b>리스너 수신 횟수</b>를 직접 센다.
	 */
	@Test
	@DisplayName("S3 — 같은 구독 주문의 재요청은 이벤트를 다시 발행하지 않고 구독도 하나뿐이다")
	void 재요청은_구독을_중복_생성하지_않는다() {
		UserId user = new UserId(9203L);
		OrderId order = new OrderId("flow-s3-replay");
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);

		paymentPurchase.grant(user, order);
		GrantResult replay = paymentPurchase.grant(user, order);

		assertThat(replay.granted()).as("재요청은 오류가 아니다 — 성공으로 답한다").isTrue();
		assertThat(replay.productType()).isEqualTo(ProductType.SUBSCRIPTION);
		verify(subscriptionGrantedListener, times(1)).on(any(SubscriptionGranted.class));
		assertThat(subscriptionRepository.count()).isEqualTo(1);
	}

	// ── S4 — 동기 수신이라 발행 레지스트리를 쓰지 않는다 ─────────────────

	/**
	 * S4 — 🔴 <b>두 이벤트 모두 동기 수신이라 {@code event_publication} 에 남지 않는다.</b>
	 *
	 * <p>이 단언이 빨개지면 리스너가 트랜잭셔널 계열({@code @ApplicationModuleListener})로 되돌아간
	 * 것이다. 그 순간 이벤트가 JSON 으로 저장되는데 {@code UserId}·{@code OrderId} 는 Jackson 이
	 * 읽을 프로퍼티가 없어 {@code {}} 가 저장된다 — 페이로드를 원시 타입으로 바꿔야 한다.
	 */
	@Test
	@DisplayName("S4 — 두 지급 이벤트 모두 동기 수신이라 발행 레지스트리에 기록되지 않는다")
	void 지급_이벤트는_레지스트리에_남지_않는다() {
		UserId buyer = new UserId(9205L);
		UserId subscriber = new UserId(9204L);

		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(buyer, new OrderId("flow-s4-consumable"));
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);
		paymentPurchase.grant(subscriber, new OrderId("flow-s4-subscription"));

		assertThat(balanceOf(buyer).orElseThrow().getBalance())
			.as("동기 수신이라 grant 반환 시점에 잔량이 확정돼 있다").isEqualTo(new Balance(1L));
		assertThat(subscriptionOf(subscriber))
			.as("동기 수신이라 grant 반환 시점에 구독이 확정돼 있다").isPresent();
		assertThat(publications(ConsumableGranted.class)).isZero();
		assertThat(publications(SubscriptionGranted.class))
			.as("기록이 생겼다면 리스너가 트랜잭셔널 계열로 되돌아간 것이다 — 직렬화 표면이 필요해진다")
			.isZero();
	}

	// ── S5 — 재구독은 새 계약을 연다 ────────────────────────────────────

	/** S5 — 같은 사용자가 다른 주문으로 다시 구독하면 새 계약이 열린다 (2026-08-16 재구독 허용). */
	@Test
	@DisplayName("S5 — 같은 사용자가 다른 주문으로 재구독하면 원장도 구독도 두 건이 된다")
	void 재구독은_이벤트로_새_계약을_연다() {
		UserId user = new UserId(9207L);
		토스가_답한다(OrderStatus.PURCHASED, SUBSCRIPTION_SKU);
		OrderId first = new OrderId("flow-s5-first");
		OrderId second = new OrderId("flow-s5-second");

		paymentPurchase.grant(user, first);
		GrantResult resubscribed = paymentPurchase.grant(user, second);

		assertThat(resubscribed.granted()).isTrue();
		assertThat(orderRepository.findByOrderId(second))
			.as("원장이 없으면 결제는 됐는데 기록만 사라진 상태가 된다").isPresent();
		assertThat(subscriptionsOf(user)).hasSize(2);
		assertThat(subscriptionRepository.findByOrderId(second))
			.as("새 계약이 없으면 재확인·게이트가 과거 계약만 본다").isPresent();
		assertThat(subscriptionRepository.findByOrderId(first))
			.as("옛 계약이 사라지면 지각 웹훅이 갈 곳을 잃는다").isPresent();
	}

	// ── helpers ────────────────────────────────────────────────────────

	private void 토스가_답한다(OrderStatus status, String sku) {
		when(tossOrderClient.statusOf(any())).thenReturn(TossOrderStatus.of(status.name(), sku));
	}

	private Optional<Subscription> subscriptionOf(UserId userId) {
		return subscriptionRepository.findAll().stream()
			.filter(row -> row.getUserId().equals(userId))
			.findFirst();
	}

	private List<Subscription> subscriptionsOf(UserId userId) {
		return subscriptionRepository.findAll().stream()
			.filter(row -> row.getUserId().equals(userId))
			.toList();
	}

	private Optional<CreditBalance> balanceOf(UserId userId) {
		return creditBalanceRepository.findAll().stream()
			.filter(row -> row.getUserId().equals(userId))
			.findFirst();
	}

	private int publications(Class<?> eventType) {
		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from event_publication where event_type = ?",
			Integer.class, eventType.getName());
		return count == null ? 0 : count;
	}
}
