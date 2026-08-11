package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.TicketSource;
import kang20.ytcreator.payment.dto.UsageTicketView;
import kang20.ytcreator.payment.internal.entity.CreditBalance;
import kang20.ytcreator.payment.internal.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.internal.client.TossOrderClient;
import kang20.ytcreator.payment.internal.repository.UsageTicketRepository;
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
 * payment-design.md §6-1 <b>C3</b>(잔량 1 에 동시 작업 N) · <b>C4</b>(같은 티켓 중복 release) —
 * 불변식 2 "잔량은 절대 음수가 되지 않는다" · 불변식 3 "티켓은 한 번만 확정·해제된다".
 *
 * <p>🔴 소모는 <b>멱등이 못 덮는 축</b>이다(payment.md §4-5-1) — grant 는 orderId 로 보호되지만
 * 소모에는 자연 멱등 키가 없다. 더블탭·병렬 요청이 정상 경로다.
 *
 * <p>⚠️ 실제 멀티스레드로 재현한다 — 함정 ⑤(읽고→빼고→저장)·함정 ⑥(무조건 +1 release)은
 * 목으로는 절대 드러나지 않는다(§10).
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, CreditConsumeConcurrencyTest.FixedClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU,
	"spring.datasource.hikari.maximum-pool-size=40"
})
class CreditConsumeConcurrencyTest {

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return new MutableClock(PaymentFixture.BASE_TIME);
		}
	}

	private static final int THREADS = 16;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private CreditBalanceRepository creditRepository;

	@Autowired
	private UsageTicketRepository usageTicketRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void clean() {
		usageTicketRepository.deleteAll();
		creditRepository.deleteAll();
	}

	/**
	 * 🔴 C3 — 잔량 1 에 동시 N 건 {@code reserve}. 요구 결과: <b>정확히 1건 성공 · 나머지 PAY_001 ·
	 * 잔량 0 · 음수 없음</b>. 함정 ⑤가 뚫리면 "작업은 2건 돌고 돈은 1건만 받았다"가 된다(§6-2).
	 * 조건부 UPDATE 의 영향 행 수 판정(§6-4)이 실물로 검증되는 지점이다.
	 */
	@Test
	@DisplayName("잔량 1에 동시 N건 reserve — 정확히 1건만 성공하고 잔량은 0, 음수는 없다")
	void C3_잔량_1_동시_소모() throws Exception {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));

		List<Outcome> outcomes = race(THREADS, () -> {
			try {
				return Outcome.success(paymentService.reserve(user));
			} catch (BusinessException e) {
				return Outcome.rejected(e.getErrorCode());
			}
		});

		List<Outcome> successes = outcomes.stream().filter(Outcome::succeeded).toList();
		assertThat(successes).as("§6-1 C3 — 한쪽만 성공").hasSize(1);
		assertThat(successes.getFirst().ticket().source()).isEqualTo(TicketSource.CREDIT);

		assertThat(outcomes.stream().filter(outcome -> !outcome.succeeded()))
			.hasSize(THREADS - 1)
			.allSatisfy(outcome -> assertThat(outcome.errorCode())
				.as("실패는 전부 PAY_001(결제 유도)이어야 한다 — 500 이면 프론트 분기가 죽는다")
				.isEqualTo(ErrorCode.PAY_001));

		assertThat(creditRepository.findByUserId(user).orElseThrow().getBalance())
			.as("§6-1 불변식 2 — 음수 금지. 0 미만이면 조건부 UPDATE 가 깨진 것")
			.isEqualTo(0);
		assertThat(usageTicketRepository.count()).as("티켓도 정확히 1장").isEqualTo(1);
	}

	/**
	 * C4 — 같은 티켓에 동시 {@code release} N 회(작업 도메인의 재시도). 요구 결과: <b>+1 정확히 1회</b>.
	 * 함정 ⑥(무조건 +1)이 뚫리면 무료 이용권이 샌다 — 상태 전이 조건부 UPDATE(§6-4)의 실물 검증.
	 */
	@Test
	@DisplayName("같은 티켓에 동시 release N회 — 잔량 +1 은 정확히 한 번이다")
	void C4_중복_release() throws Exception {
		UserId user = PaymentFixture.uniqueUser();
		creditRepository.saveAndFlush(new CreditBalance(user, 1));
		UsageTicketView ticket = paymentService.reserve(user);   // 잔량 1 → 0, RESERVED 티켓
		assertThat(creditRepository.findByUserId(user).orElseThrow().getBalance()).isZero();

		race(THREADS, () -> {
			paymentService.release(ticket.ticketId());
			return Outcome.released();
		});

		assertThat(creditRepository.findByUserId(user).orElseThrow().getBalance())
			.as("U8 되돌림은 한 번만 — 두 번이면 무료 이용권 유출(§6-2 함정 ⑥)")
			.isEqualTo(1);
	}

	private record Outcome(UsageTicketView ticket, ErrorCode errorCode) {
		static Outcome success(UsageTicketView ticket) {
			return new Outcome(ticket, null);
		}

		static Outcome rejected(ErrorCode errorCode) {
			return new Outcome(null, errorCode);
		}

		static Outcome released() {
			return new Outcome(null, null);
		}

		boolean succeeded() {
			return ticket != null;
		}
	}

	private List<Outcome> race(int threads, Callable<Outcome> call) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<Outcome>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return call.call();
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("동시 소모가 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();

		List<Outcome> outcomes = new ArrayList<>();
		for (Future<Outcome> future : futures) {
			try {
				outcomes.add(future.get());
			} catch (ExecutionException e) {
				fail("동시 소모가 비즈니스 예외가 아닌 예외로 끝났다 — 잔량 부족은 PAY_001 이어야 한다",
					e.getCause());
			}
		}
		return outcomes;
	}

	private static void awaitGate(CyclicBarrier gate) {
		try {
			gate.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (BrokenBarrierException | java.util.concurrent.TimeoutException e) {
			throw new IllegalStateException(e);
		}
	}
}
