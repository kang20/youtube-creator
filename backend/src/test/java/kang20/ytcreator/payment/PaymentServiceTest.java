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

/**
 * 지급 흐름 — {@code PaymentPurchasePort.grant} (new-domain/payment.md 결제와 지급 · 선점 · 멱등).
 *
 * <p>토스만 목으로 막고 <b>DB 는 진짜를 쓴다</b> — 멱등의 근거가 {@code UNIQUE(order_id)} 이므로
 * 저장소를 목으로 바꾸면 검증 대상이 통째로 사라진다.
 *
 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 않는다.</b> 붙이면 {@code REQUIRES_NEW} 쓰기와 경쟁
 * 재조회가 테스트 트랜잭션에 흡수돼 설계 전제를 검증하지 못한다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
class PaymentServiceTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";
	private static final OrderId ORDER = new OrderId(RAW);
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);

	/** {@code application-test.yml} 의 ytcreator.payment.*.sku 와 같아야 한다. */
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

	// ── 지급 ────────────────────────────────────────────────────────────

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

	/**
	 * "결제는 됐고 지급이 실패한 상태" — 우리는 지급 대상으로 본다.
	 * ⚠️ 문서 근거가 없는 판단이라 뒤집힐 수 있다. 뒤집히면 이 테스트가 먼저 빨개진다.
	 */
	@Test
	@DisplayName("PAYMENT_COMPLETED 도 지급 대상이다")
	void 지급_대상_판정() {
		토스가_답한다(OrderStatus.PAYMENT_COMPLETED, ONE_TIME_SKU);

		assertThat(paymentPurchase.grant(OWNER, ORDER).granted()).isTrue();
	}

	// ── 멱등 ────────────────────────────────────────────────────────────

	/**
	 * 🔴 중복 요청은 오류가 아니라 정상이다. 오류로 만들면 클라이언트가 미결을 닫지 못해
	 * 복원 흐름 자체가 깨진다.
	 */
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

	/** 이미 지급된 주문이면 토스를 부르지 않는다 — 30초 예산을 아끼는 선판정이다. */
	@Test
	@DisplayName("이미 지급된 주문은 토스를 부르지 않는다")
	void 재요청은_토스를_부르지_않는다() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(OWNER, ORDER);
		org.mockito.Mockito.clearInvocations(tossOrderClient);

		paymentPurchase.grant(OWNER, ORDER);

		verify(tossOrderClient, never()).statusOf(any());
	}

	// ── 선점 ────────────────────────────────────────────────────────────

	/** 🔴 주문 식별자를 아는 것 = 미지급 주문을 가로챌 수 있는 것. 소유자는 바뀌지 않는다. */
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

	/** ⚠️ 예외 메시지에 주문 식별자 원문이 실리면 안 된다. */
	@Test
	@DisplayName("거부 메시지에 주문 식별자 원문이 실리지 않는다")
	void 주문_식별자_비노출() {
		토스가_답한다(OrderStatus.PURCHASED, ONE_TIME_SKU);
		paymentPurchase.grant(OWNER, ORDER);

		assertThatThrownBy(() -> paymentPurchase.grant(OTHER, ORDER))
			.hasMessageNotContaining(RAW);
	}

	// ── 지급 거부 ────────────────────────────────────────────────────────

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

	/** 봉투 실패·전송 실패·타임아웃·비활성 — 전부 "확인하지 못했다"다. 지급하지 않는다. */
	@Test
	@DisplayName("주문을 확인하지 못하면 지급하지 않는다")
	void 검증_실패() {
		when(tossOrderClient.statusOf(any())).thenReturn(TossOrderStatus.unavailable());

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_006);

		assertThat(orderRepository.count()).isZero();
	}

	/** 🔴 카탈로그에 없는 상품 코드는 남의 상품이다 — 결제됐다고 해서 우리가 지급하지 않는다. */
	@Test
	@DisplayName("모르는 상품 코드는 지급하지 않는다")
	void 남의_상품() {
		토스가_답한다(OrderStatus.PURCHASED, "someone.else.product");

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_004);

		assertThat(orderRepository.count()).isZero();
	}

	/** 상품 코드가 없는 응답(상태를 확정하지 못한 경우)도 같은 경로다. */
	@Test
	@DisplayName("상품 코드가 없으면 지급하지 않는다")
	void 상품_코드_없음() {
		when(tossOrderClient.statusOf(any()))
			.thenReturn(TossOrderStatus.of(OrderStatus.PURCHASED.name(), null));

		assertThatThrownBy(() -> paymentPurchase.grant(OWNER, ORDER))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_004);
	}

	// ── 입력 방어 ────────────────────────────────────────────────────────

	/**
	 * 빈 주문 식별자는 <b>포트에 닿지도 못한다</b> — 인자를 만드는 순간 걸린다.
	 * 서비스가 방어하던 것을 타입이 가져갔다(값 규칙은 {@link OrderIdTest} 가 본다).
	 */
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
