package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.internal.service.PaymentService;
import kang20.ytcreator.payment.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.internal.handler.outbound.repository.PaymentOrderRepository;
import kang20.ytcreator.payment.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.UsageTicketRepository;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * payment-design.md §6-1 <b>C1</b>(같은 orderId 동시 지급) · <b>C2</b>(같은 사용자의 다른 두 주문
 * 동시 지급 — 잔량 행 생성 경쟁) — 불변식 1 "한 orderId 로 지급되는 이용권은 정확히 한 번".
 *
 * <p>⚠️ <b>실제 멀티스레드로 재현한다</b>(§10) — 목으로 {@code DuplicateKeyException} 을 흉내 내면
 * §6-5 의 트랜잭션 경계(REQUIRES_NEW 롤백 + 새 트랜잭션 재조회)가 전혀 검증되지 않는다.
 * C1 은 반드시 일어난다 — 복원(§6-3)은 앱 기동마다 도는 흐름이다.
 *
 * <p>⚠️ <b>테스트에 {@code @Transactional} 을 붙이지 않는다.</b> {@code grant} 를 트랜잭션 안에서
 * 부르면 §6-2 함정 ③·④가 되살아나 경쟁이 재현되지 않는다(auth 선례 그대로).
 *
 * <p>커넥션 풀을 스레드 수보다 크게 잡는다 — 풀이 좁으면 스레드가 직렬화되어 경쟁이 조용히 사라진다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, PaymentGrantConcurrencyTest.FixedClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU,
	"spring.datasource.hikari.maximum-pool-size=40"
})
class PaymentGrantConcurrencyTest {

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
		when(tossOrderClient.statusOf(anyString()))
			.thenReturn(TossOrderStatus.of("PURCHASED", PaymentFixture.ONE_TIME_SKU));
	}

	/**
	 * 🔴 C1 — 콜백과 복원이 같은 orderId 를 동시에 보낸다(정상 경로 — payment.md §4-5).
	 * 요구 결과: <b>주문 1행 · 잔량 정확히 +1 · 전원 200(예외 금지)</b>.
	 * 함정 ①("조회 후 없으면 삽입"은 잔량 +2)이 실제로 막혔는지를 UNIQUE + REQUIRES_NEW 실물로 본다.
	 */
	@Test
	@DisplayName("같은 orderId 로 동시에 지급해도 주문은 1행, 잔량은 정확히 +1 이다")
	void C1_같은_주문_동시_지급() throws Exception {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("c1");

		List<GrantResult> results = race(THREADS, () -> paymentService.grant(user, orderId));

		assertThat(results).hasSize(THREADS);
		assertThat(results).allSatisfy(result -> assertThat(result.granted()).isTrue());
		assertThat(orderRepository.count()).isEqualTo(1);
		assertThat(creditRepository.findByUserId(user).orElseThrow().getBalance())
			.as("§6-1 불변식 1 — 잔량 부풀림은 무료 이용권 유출이다(R3)")
			.isEqualTo(1);
	}

	/**
	 * C1 변형 — 경쟁 패자도 <b>정상 200 멱등</b>을 받는다(§6-5 판정 매트릭스 "경쟁에서 짐(내 것)").
	 * 패자의 재조회(④)가 승자의 커밋을 보는 것은 {@code DuplicateKeyException} + 새 트랜잭션일 때만
	 * 참이다(§6-5) — 이 단언이 그 전제의 실물 검증이다.
	 */
	@Test
	@DisplayName("C1 경쟁 패자도 현재 잔량이 담긴 정상 응답을 받는다")
	void C1_패자도_정상_응답() throws Exception {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("c1-loser");

		List<GrantResult> results = race(THREADS, () -> paymentService.grant(user, orderId));

		// 승자든 패자든 응답 모양이 같아야 한다(payment.md §4-5-1 — 재요청 200 의 credits 는 현재 잔량)
		assertThat(results).allSatisfy(result -> {
			assertThat(result.granted()).isTrue();
			assertThat(result.entitlement().credits()).isEqualTo(1);
		});
	}

	/**
	 * C2 — 같은 사용자의 <b>다른 두 주문</b>이 동시에 온다(단건 연속 구매·복원의 병렬 처리).
	 * 잔량 행 생성(insert)이 겹치는 경쟁이다(§6-4) — 요구 결과: 잔량 정확히 +N.
	 * round-1-dev 판단 3(재시도를 writer 전체 재호출로) 의 실물 검증이 여기다.
	 */
	@Test
	@DisplayName("같은 사용자의 다른 주문 N건을 동시에 지급하면 잔량은 정확히 N 이다")
	void C2_다른_주문_동시_지급() throws Exception {
		UserId user = PaymentFixture.uniqueUser();
		List<String> orderIds = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			orderIds.add(PaymentFixture.uniqueOrder("c2-" + i));
		}

		CyclicBarrier gate = new CyclicBarrier(THREADS);
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		List<Future<GrantResult>> futures = new ArrayList<>();
		try {
			for (String orderId : orderIds) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return paymentService.grant(user, orderId);
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		collect(futures);

		assertThat(orderRepository.count()).isEqualTo(THREADS);
		assertThat(creditRepository.findByUserId(user).orElseThrow().getBalance())
			.as("§6-4 — insert 경쟁 패자의 1회 재시도(increment)가 지급을 잃으면 돈 내고 못 쓴다")
			.isEqualTo(THREADS);
	}

	/** §6-2 전제 — 이 테스트 자신이 트랜잭션 밖에서 부르고 있는지 고정한다 */
	@Test
	@DisplayName("이 테스트는 트랜잭션 밖에서 grant 를 부른다 — §6-2 전제를 스스로 지킨다")
	void 트랜잭션_밖에서_호출한다() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("트랜잭션 안이면 함정 ③·④가 되살아나 경쟁이 재현되지 않는다")
			.isFalse();
	}

	// ── 경쟁 도우미 (auth AuthConcurrencyTest 선례 — Thread.sleep 금지) ──

	private List<GrantResult> race(int threads, Callable<GrantResult> call) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<GrantResult>> futures = new ArrayList<>();
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
			.as("동시 지급이 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();
		return collect(futures);
	}

	private static List<GrantResult> collect(List<Future<GrantResult>> futures) {
		List<GrantResult> results = new ArrayList<>();
		for (Future<GrantResult> future : futures) {
			try {
				results.add(future.get());
			} catch (ExecutionException e) {
				fail("동시 지급이 예외로 끝났다 — §6-1 C1 요구는 '전원 200'이다", e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return results;
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
