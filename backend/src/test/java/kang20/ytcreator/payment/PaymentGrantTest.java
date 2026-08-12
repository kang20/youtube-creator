package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.service.PaymentService;
import kang20.ytcreator.payment.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.internal.entity.PaymentOrder;
import kang20.ytcreator.payment.internal.handler.outbound.repository.PaymentOrderRepository;
import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.UsageTicketRepository;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * U2(검증 후 지급) · U3(멱등) · U4(선점) · U12(미결 복원 지원) — payment-design.md §5-2 · §10.
 *
 * <p>⚠️ <b>트랜잭션을 열지 않는다.</b> {@code grant} 는 트랜잭션 밖 호출이 전제다(§6-2) —
 * 테스트가 트랜잭션을 열면 §6-5 ④(경쟁 패자 재조회)가 스냅샷에 갇혀 검증이 무의미해진다.
 *
 * <p>토스는 실호출이 불가능하므로 {@link TossOrderClient} 를 목으로 대체한다 — §10 이 허용한
 * 유일한 목이다(경쟁 예외를 목으로 흉내 내는 것은 금지 — 그건 동시성 테스트가 실물로 재현한다).
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, PaymentGrantTest.FixedClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU
})
class PaymentGrantTest {

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return new MutableClock(PaymentFixture.BASE_TIME);
		}
	}

	@Autowired
	private PaymentService paymentService;


	@Autowired
	private PaymentOrderRepository orderRepository;

	@Autowired
	private CreditBalanceRepository creditRepository;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private UsageTicketRepository usageTicketRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void clean() {
		usageTicketRepository.deleteAll();
		subscriptionRepository.deleteAll();
		creditRepository.deleteAll();
		orderRepository.deleteAll();
	}

	private void tossAnswers(String orderId, String status, String sku) {
		when(tossOrderClient.statusOf(orderId)).thenReturn(TossOrderStatus.of(status, sku));
	}

	private int creditsOf(UserId userId) {
		return creditRepository.findByUserId(userId).map(balance -> balance.getBalance()).orElse(0);
	}

	// ── U2 — 검증 후 지급 ───────────────────────────────────────────────

	/** U2 — 단건: 토스가 PURCHASED 라 답한 주문만 잔량 +1. 주문 원장 1행 */
	@Test
	@DisplayName("PURCHASED 단건 주문은 횟수권 +1 로 지급된다")
	void 단건_최초_지급() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-one");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		GrantResult result = paymentService.grant(user, orderId);

		assertThat(result.granted()).isTrue();
		assertThat(result.productType()).isEqualTo(ProductType.CONSUMABLE);
		assertThat(result.entitlement().accessible()).isTrue();
		assertThat(result.entitlement().credits()).isEqualTo(1);
		assertThat(creditsOf(user)).isEqualTo(1);

		PaymentOrder order = orderRepository.findByOrderId(orderId).orElseThrow();
		assertThat(order.getId()).isNotNull();
		assertThat(order.getOrderId()).isEqualTo(orderId);
		assertThat(order.getUserId()).isEqualTo(user);
		assertThat(order.getSku()).isEqualTo(PaymentFixture.ONE_TIME_SKU);
		assertThat(order.getProductType()).isEqualTo(ProductType.CONSUMABLE);
	}

	/** U2 · §4-1 — 단건 재구매는 새 orderId 의 새 주문이다. 잔량이 누적된다(멱등은 orderId 단위) */
	@Test
	@DisplayName("다른 orderId 의 재구매는 잔량을 누적한다")
	void 단건_재구매_누적() {
		UserId user = PaymentFixture.uniqueUser();
		String first = PaymentFixture.uniqueOrder("grant-again-1");
		String second = PaymentFixture.uniqueOrder("grant-again-2");
		tossAnswers(first, "PURCHASED", PaymentFixture.ONE_TIME_SKU);
		tossAnswers(second, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		paymentService.grant(user, first);
		GrantResult result = paymentService.grant(user, second);

		assertThat(result.entitlement().credits()).isEqualTo(2);
		assertThat(creditsOf(user)).isEqualTo(2);
		assertThat(orderRepository.count()).isEqualTo(2);
	}

	/** U2 · §4-7-1④ — 구독: ACTIVE·추정 만료(결제일+31일)·웹훅 미수신 상태로 태어난다 */
	@Test
	@DisplayName("구독 주문은 기간권을 만들고 만료는 결제일+31일 추정값이다")
	void 구독_최초_지급() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-sub");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.SUBSCRIPTION_SKU);

		GrantResult result = paymentService.grant(user, orderId);

		assertThat(result.productType()).isEqualTo(ProductType.SUBSCRIPTION);
		assertThat(result.entitlement().accessible()).isTrue();
		assertThat(result.entitlement().subscription().status()).isEqualTo("ACTIVE");

		Subscription subscription = subscriptionRepository.findByUserId(user).orElseThrow();
		assertThat(subscription.getOrderId())
			.as("웹훅이 이 값으로 찾아온다(§5-4) — 최초 주문 ID 가 구독의 핸들이다")
			.isEqualTo(orderId);
		assertThat(subscription.getUserId()).isEqualTo(user);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(PaymentFixture.BASE_TIME.plusDays(31));
		assertThat(subscription.isExpiresAtEstimated()).isTrue();
		assertThat(subscription.isAutoRenew()).isTrue();
		assertThat(subscription.getLastWebhookOccurredAt()).isNull();
	}

	/** ✅-7 · §12-4 — 응답의 expiresAt 은 +09:00 오프셋을 붙인다. 프론트에 모호한 값을 넘기지 않는다 */
	@Test
	@DisplayName("응답의 expiresAt 은 KST 오프셋(+09:00)이 붙어 있다")
	void expiresAt_은_KST_오프셋이다() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-offset");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.SUBSCRIPTION_SKU);

		GrantResult result = paymentService.grant(user, orderId);

		assertThat(result.entitlement().subscription().expiresAt().getOffset())
			.isEqualTo(ZoneOffset.ofHours(9));
	}

	/** ✅-1 — PAYMENT_COMPLETED 도 지급 대상이다(문서 근거 없는 확정 — 뒤집히면 재결정) */
	@Test
	@DisplayName("PAYMENT_COMPLETED 도 지급된다(✅-1)")
	void PAYMENT_COMPLETED_도_지급() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-pc");
		tossAnswers(orderId, "PAYMENT_COMPLETED", PaymentFixture.ONE_TIME_SKU);

		assertThat(paymentService.grant(user, orderId).granted()).isTrue();
		assertThat(creditsOf(user)).isEqualTo(1);
	}

	// ── U3 · U12 — 멱등 · 복원 재전송 ───────────────────────────────────

	/**
	 * U3 · U12 — 같은 orderId 재요청은 에러가 아니라 200 이고 <b>잔량이 그대로</b>다.
	 * 복원(§6-3)이 이미 지급된 주문을 재전송하는 것이 정상 경로라(payment.md §4-5),
	 * 여기가 곧 U12(미결 주문 복원 지원)의 구현이다.
	 */
	@Test
	@DisplayName("같은 orderId 재요청은 200 이고 잔량은 그대로다 — 멱등이 U12 복원의 구현이다")
	void 재요청은_멱등_200() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-replay");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		paymentService.grant(user, orderId);
		GrantResult replay = paymentService.grant(user, orderId);
		GrantResult replayAgain = paymentService.grant(user, orderId);

		assertThat(replay.granted()).isTrue();
		assertThat(replayAgain.granted()).isTrue();
		// payment.md §4-5-1 — 재요청 200 의 credits 는 "현재 잔량"이다. +1 이 다시 반영되면 멱등이 깨진 것
		assertThat(replay.entitlement().credits()).isEqualTo(1);
		assertThat(creditsOf(user)).isEqualTo(1);
		assertThat(orderRepository.count()).isEqualTo(1);
	}

	/** §5-2 — 멱등 선판정이 토스 앞에 있다. 복원 재전송이 QPM 을 소모하면 안 된다 */
	@Test
	@DisplayName("이미 지급된 주문의 재요청은 토스를 다시 부르지 않는다")
	void 재요청은_토스를_부르지_않는다() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-no-toss");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		paymentService.grant(user, orderId);
		paymentService.grant(user, orderId);

		verify(tossOrderClient, times(1)).statusOf(anyString());
	}

	// ── U4 — 소유권 선점 ────────────────────────────────────────────────

	/** U4 · §4-4 — 한 주문은 한 익명키(UserId)에만 귀속. 남의 것이면 PAY_005 */
	@Test
	@DisplayName("남의 주문을 지급 요청하면 PAY_005 다")
	void 남의_주문은_PAY_005() {
		UserId owner = PaymentFixture.uniqueUser();
		UserId intruder = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-preempt");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		paymentService.grant(owner, orderId);

		assertThatThrownBy(() -> paymentService.grant(intruder, orderId))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_005);

		// 선점 판정은 토스 조회 이전이다(payment.md §5-4 표) — 침입자 몫의 호출이 없어야 한다
		verify(tossOrderClient, times(1)).statusOf(anyString());
		assertThat(creditsOf(intruder)).isZero();
	}

	// ── 토스 status → 응답 매핑 (payment.md §5-4 표 전수) ────────────────

	/** payment.md §5-4 — 지급 불가 status 매핑 전수. 상태는 바뀌지 않아야 한다 */
	@Test
	@DisplayName("지급 불가 status 는 §5-4 표의 에러 코드로 거부되고 아무것도 지급되지 않는다")
	void 지급_불가_status_매핑_전수() {
		record Case(String status, ErrorCode expected) {
		}
		for (Case c : new Case[] {
				new Case("ORDER_IN_PROGRESS", ErrorCode.PAY_002),
				new Case("FAILED", ErrorCode.PAY_003),
				new Case("REFUNDED", ErrorCode.PAY_003),
				new Case("NOT_FOUND", ErrorCode.PAY_004),
				new Case("MINIAPP_MISMATCH", ErrorCode.PAY_004),
				new Case("ERROR", ErrorCode.PAY_006)}) {
			UserId user = PaymentFixture.uniqueUser();
			String orderId = PaymentFixture.uniqueOrder("grant-reject");
			tossAnswers(orderId, c.status(), PaymentFixture.ONE_TIME_SKU);

			assertThatThrownBy(() -> paymentService.grant(user, orderId))
				.as("status=%s", c.status())
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(c.expected());

			assertThat(orderRepository.findByOrderId(orderId)).as("주문이 남으면 안 된다").isEmpty();
			assertThat(creditsOf(user)).isZero();
		}
	}

	/** R9 · §5-6 — 봉투 실패(resultType != SUCCESS)·타임아웃은 PAY_006. 복원(§6-3)에 위임된다 */
	@Test
	@DisplayName("토스 장애(unavailable)는 PAY_006 이다")
	void 토스_장애는_PAY_006() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-down");
		when(tossOrderClient.statusOf(orderId)).thenReturn(TossOrderStatus.unavailable());

		assertThatThrownBy(() -> paymentService.grant(user, orderId))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_006);
	}

	/** §5-2③ — 토스 sku 가 우리 카탈로그에 없으면 남의 상품이다(PAY_004) */
	@Test
	@DisplayName("카탈로그에 없는 sku 는 PAY_004 다")
	void 남의_상품은_PAY_004() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("grant-foreign");
		tossAnswers(orderId, "PURCHASED", PaymentFixture.FOREIGN_SKU);

		assertThatThrownBy(() -> paymentService.grant(user, orderId))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_004);

		assertThat(orderRepository.findByOrderId(orderId)).isEmpty();
	}

	// ── §3 — 한 사용자에게 활성 구독은 하나 ─────────────────────────────

	/**
	 * payment-design.md §3 — {@code UNIQUE(user_id)}: 플랜이 하나뿐이라 두 번째 활성 구독은
	 * 정상 상태가 아니다. DB 가 거부하고 PAY_003 으로 떨어진다(조용히 두 개보다 낫다).
	 */
	@Test
	@DisplayName("이미 구독 중인 사용자의 두 번째 구독 주문은 PAY_003 이다")
	void 두번째_구독은_PAY_003() {
		UserId user = PaymentFixture.uniqueUser();
		String first = PaymentFixture.uniqueOrder("grant-sub-1");
		String second = PaymentFixture.uniqueOrder("grant-sub-2");
		tossAnswers(first, "PURCHASED", PaymentFixture.SUBSCRIPTION_SKU);
		tossAnswers(second, "PURCHASED", PaymentFixture.SUBSCRIPTION_SKU);

		paymentService.grant(user, first);

		assertThatThrownBy(() -> paymentService.grant(user, second))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_003);

		// §6-5 원자성 — 주문 원장과 구독은 함께 롤백된다. 두 번째 주문이 원장에 남으면
		// 재요청이 멱등 200 을 받아 "구독이 없는데 지급됐다"가 된다
		assertThat(orderRepository.findByOrderId(second)).isEmpty();
		assertThat(subscriptionRepository.findByUserId(user).orElseThrow().getOrderId()).isEqualTo(first);
	}

	// ── §6-3 — UNIQUE 아닌 무결성 위반은 경쟁이 아니다 (결함 ④ 음성 케이스) ──

	/**
	 * §6-3 의도(결함 ④ 음성) — <b>NOT NULL 위반은 경쟁 분기로 흘러들지 않는다.</b>
	 * 구독 sku 로 재현하는 이유: 경쟁 분기로 오판되면 "주문 행 없음 + SUBSCRIPTION" 경로가
	 * <b>PAY_003(가짜 비즈니스 에러)</b>을 만들어 낸다 — 원본 예외가 그대로 나가야 500 안전망이 잡는다.
	 */
	@Test
	@DisplayName("NOT NULL 위반은 경쟁 분기로 흘러들지 않는다 — PAY_003 이 아니라 원본 예외다")
	void NOT_NULL_위반은_경쟁이_아니다() {
		UserId user = PaymentFixture.uniqueUser();
		// orderId null → payment_orders.order_id NOT NULL 위반. UNIQUE 가 아닌 무결성 위반의 대표
		tossAnswers(null, "PURCHASED", PaymentFixture.SUBSCRIPTION_SKU);

		assertThatThrownBy(() -> paymentService.grant(user, null))
			.as("§6-3 — kind != UNIQUE 는 원본을 그대로 던진다. BusinessException(PAY_003)이면 오판이다")
			.isInstanceOf(DataIntegrityViolationException.class)
			.isNotInstanceOf(BusinessException.class);

		// REQUIRES_NEW 전체 롤백 — 구독도 주문도 남지 않는다(§6-5)
		assertThat(subscriptionRepository.findByUserId(user)).isEmpty();
		assertThat(orderRepository.count()).isZero();
	}

	/**
	 * §6-3 의도(결함 ④ 음성) — <b>길이 초과(orderId > 64자)도 경쟁이 아니다.</b> 컨트롤러는
	 * {@code @NotBlank} 만 보므로 과길이 orderId 는 실제 도달 가능한 입력이다. 원인 체인에
	 * Hibernate {@code ConstraintViolationException} 이 아예 없는 축(DataException)을 덮는다.
	 */
	@Test
	@DisplayName("길이 초과 orderId 는 경쟁 분기로 흘러들지 않고 원본 예외로 나간다")
	void 길이_초과는_경쟁이_아니다() {
		UserId user = PaymentFixture.uniqueUser();
		String tooLong = "order-too-long-" + "x".repeat(64);   // payment_orders.order_id length 64 초과
		tossAnswers(tooLong, "PURCHASED", PaymentFixture.ONE_TIME_SKU);

		assertThatThrownBy(() -> paymentService.grant(user, tooLong))
			.isInstanceOf(DataIntegrityViolationException.class)
			.isNotInstanceOf(BusinessException.class);

		assertThat(creditsOf(user)).isZero();
		assertThat(orderRepository.count()).isZero();
	}

	// ── U5 — 이용권 조회 (서비스 축) ────────────────────────────────────

	/** U5 · §5-3 — 이용권이 없어도 404 가 아니라 정상 응답("없음"은 정상 상태다) */
	@Test
	@DisplayName("이용권이 없는 사용자도 정상 응답을 받는다 — accessible=false·credits=0·NONE")
	void 이용권_없음도_정상_상태() {
		var entitlement = paymentService.entitlementOf(PaymentFixture.uniqueUser());

		assertThat(entitlement.accessible()).isFalse();
		assertThat(entitlement.credits()).isZero();
		assertThat(entitlement.subscriptionStale()).isFalse();
		assertThat(entitlement.subscription().status()).isEqualTo("NONE");
		assertThat(entitlement.subscription().expiresAt()).isNull();
		assertThat(entitlement.subscription().autoRenew()).isFalse();
	}

	/**
	 * U1 · payment.md §5-2 — 상품은 설정에서 읽고 가격을 담지 않는다. DB 를 보지 않는다.
	 * §5-2 원문은 상품별 <b>중첩 객체</b>이고 {@code productType} 이 클라의 주문 함수를 결정한다(K2).
	 */
	@Test
	@DisplayName("상품 카탈로그는 §5-2 중첩 구조로 설정의 sku 만 내려준다")
	void 상품_카탈로그() {
		var catalog = paymentService.products();

		assertThat(catalog.oneTime().sku()).isEqualTo(PaymentFixture.ONE_TIME_SKU);
		assertThat(catalog.oneTime().productType()).isEqualTo("CONSUMABLE");
		assertThat(catalog.subscription().sku()).isEqualTo(PaymentFixture.SUBSCRIPTION_SKU);
		assertThat(catalog.subscription().productType()).isEqualTo("SUBSCRIPTION");
		assertThat(catalog.subscription().renewalCycle()).isEqualTo("MONTHLY");
		assertThat(catalog.subscription().offerId()).isNull();
	}
}
