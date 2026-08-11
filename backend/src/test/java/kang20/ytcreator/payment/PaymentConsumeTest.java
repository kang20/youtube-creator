package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.TicketSource;
import kang20.ytcreator.payment.dto.UsageTicketView;
import kang20.ytcreator.payment.internal.CreditBalance;
import kang20.ytcreator.payment.internal.CreditBalanceRepository;
import kang20.ytcreator.payment.internal.Subscription;
import kang20.ytcreator.payment.internal.SubscriptionRepository;
import kang20.ytcreator.payment.internal.TicketStatus;
import kang20.ytcreator.payment.internal.TossOrderClient;
import kang20.ytcreator.payment.internal.UsageTicketRepository;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * U6(이용 게이트) · U7(횟수권 소모) · U8(실패 되돌림) — reserve/commit/release 정상 흐름
 * (payment-design.md §5-6 · §10 {@code PaymentConsumeTest}. 동시성은 별도 — C3·C4).
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, PaymentConsumeTest.FixedClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU
})
class PaymentConsumeTest {

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
	}

	private int creditsOf(UserId userId) {
		return creditRepository.findByUserId(userId).map(CreditBalance::getBalance).orElse(0);
	}

	/** 살아 있는 기간권(추정 만료 미도래). */
	private void activeSubscription(UserId user) {
		subscriptionRepository.saveAndFlush(new Subscription(
			PaymentFixture.uniqueOrder("consume-active"), user, PaymentFixture.BASE_TIME.plusDays(20)));
	}

	/** STALE 기간권 — 추정 만료 + 유예 1일 경과, 웹훅 미수신(§4-7-1③). */
	private void staleSubscription(UserId user) {
		subscriptionRepository.saveAndFlush(new Subscription(
			PaymentFixture.uniqueOrder("consume-stale"), user,
			PaymentFixture.BASE_TIME.minusDays(2)));
	}

	// ── U7 — 횟수권 소모 ────────────────────────────────────────────────

	/** U7 · ✅-4ⓐ — 작업 생성 시 예약으로 −1. 티켓은 RESERVED 로 태어난다 */
	@Test
	@DisplayName("횟수권 사용자의 reserve 는 잔량 -1 과 CREDIT 티켓이다")
	void 횟수권_소모() {
		UserId user = PaymentFixture.uniqueUser();
		CreditBalance balance = creditRepository.saveAndFlush(new CreditBalance(user, 2));
		assertThat(balance.getId()).isNotNull();
		assertThat(balance.getUserId()).isEqualTo(user);

		UsageTicketView ticket = paymentService.reserve(user);

		assertThat(ticket.source()).isEqualTo(TicketSource.CREDIT);
		assertThat(ticket.ticketId()).isNotNull();
		assertThat(creditsOf(user)).isEqualTo(1);
		var stored = usageTicketRepository.findById(ticket.ticketId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TicketStatus.RESERVED);
		assertThat(stored.getUserId()).isEqualTo(user);
	}

	/**
	 * U7 · ✅-4ⓒ — <b>기간권 우선.</b> 기간권 보유자는 차감하지 않는다 — 구독 중 횟수권을 보존한다
	 * ("구독 중인데 왜 내 이용권이 줄지?" 문의 차단 — payment.md §4-3).
	 * 호출자(subtitle)가 이용권 종류로 분기하지 않도록 티켓은 발급된다(§4).
	 */
	@Test
	@DisplayName("기간권 사용자의 reserve 는 SUBSCRIPTION 티켓이고 잔량은 그대로다")
	void 기간권_우선_차감_없음() {
		UserId user = PaymentFixture.uniqueUser();
		activeSubscription(user);
		creditRepository.saveAndFlush(new CreditBalance(user, 3));

		UsageTicketView ticket = paymentService.reserve(user);

		assertThat(ticket.source()).isEqualTo(TicketSource.SUBSCRIPTION);
		assertThat(creditsOf(user)).as("✅-4ⓒ — 기간권 보유자는 차감하지 않는다(U7)").isEqualTo(3);
	}

	// ── U6 — 이용 게이트 ────────────────────────────────────────────────

	/** U6 · §7 — 이용권 없으면 403 PAY_001(결제 유도). 401 과 다른 프론트 행동이다 */
	@Test
	@DisplayName("이용권이 없으면 PAY_001 이고 티켓은 만들어지지 않는다")
	void 이용권_없음은_PAY_001() {
		UserId user = PaymentFixture.uniqueUser();

		assertThatThrownBy(() -> paymentService.reserve(user))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_001);

		assertThat(usageTicketRepository.count()).isZero();
	}

	/** U6 — 잔량이 0 으로 소진된 뒤에도 PAY_001. 음수로 내려가지 않는다(§6-4) */
	@Test
	@DisplayName("잔량을 다 쓰면 다음 reserve 는 PAY_001 이고 잔량은 0 에 머문다")
	void 소진_후_PAY_001() {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		paymentService.reserve(user);

		assertThatThrownBy(() -> paymentService.reserve(user))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_001);

		assertThat(creditsOf(user)).isZero();
	}

	/**
	 * 🔴 §5-6 판정 순서 — <b>PAY_007(STALE) 을 PAY_001 보다 먼저 본다.</b> 순서가 바뀌면
	 * STALE 인 유료 사용자가 결제 유도 화면을 받는다 — payment.md §6-7 이 금지한 바로 그 화면.
	 * 잔량을 함께 쥐여 줘서 "차감으로 흘러가지 않는다"까지 본다.
	 */
	@Test
	@DisplayName("STALE 구독 + 잔량 보유 사용자는 PAY_001 이 아니라 PAY_007 을 받는다")
	void STALE_은_PAY_007_이_먼저다() {
		UserId user = PaymentFixture.uniqueUser();
		staleSubscription(user);
		creditRepository.saveAndFlush(new CreditBalance(user, 5));

		assertThatThrownBy(() -> paymentService.reserve(user))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.as("§5-6 — PAY_007 이 먼저다. PAY_001 이면 이미 낸 사람에게 결제 유도가 된다(§6-7)")
			.isEqualTo(ErrorCode.PAY_007);

		assertThat(creditsOf(user)).as("STALE 판정이 차감보다 먼저라 잔량이 줄면 안 된다").isEqualTo(5);
		assertThat(usageTicketRepository.count()).isZero();
	}

	/** U5 §5-3 — STALE 은 entitlement 에도 신호로 실린다(subscriptionStale=true, 게이트 닫힘) */
	@Test
	@DisplayName("STALE 구독의 entitlement 는 accessible=false + subscriptionStale=true 다")
	void STALE_신호() {
		UserId user = PaymentFixture.uniqueUser();
		staleSubscription(user);

		var entitlement = paymentService.entitlementOf(user);

		assertThat(entitlement.accessible()).isFalse();
		assertThat(entitlement.subscriptionStale())
			.as("payment.md §5-3 — true 면 프론트는 결제 유도가 아니라 recheck 흐름으로 간다(K11)")
			.isTrue();
		assertThat(entitlement.subscription().status()).isEqualTo("ACTIVE");
	}

	// ── U8 — 실패한 작업은 소모하지 않는다 ──────────────────────────────

	/** U8 · R10 — 작업 실패 시 되돌림. 2,200원 내고 결과물을 못 받는 것이 최악의 경험이다 */
	@Test
	@DisplayName("CREDIT 티켓 release 는 잔량을 +1 되돌린다")
	void release_는_되돌린다() {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		UsageTicketView ticket = paymentService.reserve(user);
		assertThat(creditsOf(user)).isZero();

		paymentService.release(ticket.ticketId());

		assertThat(creditsOf(user)).isEqualTo(1);
		assertThat(usageTicketRepository.findById(ticket.ticketId()).orElseThrow().getStatus())
			.isEqualTo(TicketStatus.RELEASED);
	}

	/** U8 — 기간권 티켓의 release 는 되돌릴 차감이 없다. 잔량을 건드리지 않는다 */
	@Test
	@DisplayName("SUBSCRIPTION 티켓 release 는 잔량을 건드리지 않는다")
	void 기간권_release_는_잔량_불변() {
		UserId user = PaymentFixture.uniqueUser();
		activeSubscription(user);
		creditRepository.saveAndFlush(new CreditBalance(user, 2));
		UsageTicketView ticket = paymentService.reserve(user);

		paymentService.release(ticket.ticketId());

		assertThat(creditsOf(user)).isEqualTo(2);
	}

	/** §5-6 — commit: RESERVED → COMMITTED. 잔량 무변(예약 시점에 이미 차감됐다 — ✅-4ⓐ) */
	@Test
	@DisplayName("commit 은 티켓을 COMMITTED 로 확정하고 잔량을 건드리지 않는다")
	void commit_확정() {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		UsageTicketView ticket = paymentService.reserve(user);

		paymentService.commit(ticket.ticketId());

		assertThat(usageTicketRepository.findById(ticket.ticketId()).orElseThrow().getStatus())
			.isEqualTo(TicketStatus.COMMITTED);
		assertThat(creditsOf(user)).isZero();
	}

	/** §5-6 — commit·release 는 멱등. 작업 도메인이 재시도해도 상태·잔량이 흔들리지 않는다 */
	@Test
	@DisplayName("commit 재호출·commit 후 release 는 무시된다 — 확정된 소모는 되돌아오지 않는다")
	void commit_후_release_는_무시() {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		UsageTicketView ticket = paymentService.reserve(user);

		paymentService.commit(ticket.ticketId());
		paymentService.commit(ticket.ticketId());
		paymentService.release(ticket.ticketId());   // C4 축 — COMMITTED 는 되돌리지 않는다

		assertThat(usageTicketRepository.findById(ticket.ticketId()).orElseThrow().getStatus())
			.isEqualTo(TicketStatus.COMMITTED);
		assertThat(creditsOf(user)).as("확정 후 release 가 +1 하면 무료 이용권이 샌다(함정 ⑥)").isZero();
	}

	/** §5-6 — release 재호출도 멱등. +1 은 한 번만(C4 의 순차 재현) */
	@Test
	@DisplayName("release 재호출은 +1 을 반복하지 않는다")
	void release_멱등() {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		UsageTicketView ticket = paymentService.reserve(user);

		paymentService.release(ticket.ticketId());
		paymentService.release(ticket.ticketId());

		assertThat(creditsOf(user)).isEqualTo(1);
	}

	/** §5-6 — 없는 티켓은 무시(멱등 계약의 연장). 예외가 나면 작업 도메인 재시도가 죽는다 */
	@Test
	@DisplayName("존재하지 않는 티켓의 commit·release 는 조용히 무시된다")
	void 없는_티켓은_무시() {
		assertThatCode(() -> {
			paymentService.commit(new UsageTicketId(999_999L));
			paymentService.release(new UsageTicketId(999_999L));
		}).doesNotThrowAnyException();
	}
}
