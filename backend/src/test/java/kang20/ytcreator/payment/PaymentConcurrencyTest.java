package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 동시 지급 경쟁 — {@code UNIQUE(order_id)} 심판 + {@code UniqueRace} 복구
 * (new-domain/payment.md 결제와 지급 · 선점 · 멱등 · {@code UniqueRace} javadoc).
 *
 * <p>{@code PaymentServiceTest} 의 멱등 테스트는 <b>순차</b> 재요청이라 선판정({@code findByOrderId})
 * 에서 걸린다 — 그래서 "삽입에 져서 승자 행으로 맞추는" 복구 경로({@code PaymentService} 의 settle
 * 람다)는 그 테스트로 절대 실행되지 않는다. 여기서 배리어로 같은 순간에 풀어 <b>전원이 선판정에서
 * 0행을 보고 삽입으로 몰리게</b> 만든다.
 *
 * <p>⚠️ {@code @Transactional} 을 붙이지 않는다 — 붙이면 REQUIRES_NEW 삽입과 경쟁 재조회가 테스트
 * 트랜잭션에 흡수돼 설계 전제를 검증하지 못한다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class PaymentConcurrencyTest {

	/** 같은 순간에 풀 스레드 수 — 전원이 선판정에서 0행을 보게 만든다. */
	private static final int THREADS = 8;

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";
	private static final OrderId ORDER = new OrderId(RAW);
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);

	/** {@code application-test.yml} 의 ytcreator.payment.one-time.sku 와 같아야 한다. */
	private static final String ONE_TIME_SKU = "test.one-time";

	@Autowired
	private PaymentPurchasePort paymentPurchase;

	@Autowired
	private OrderRepository orderRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void 원장을_비운다() {
		orderRepository.deleteAll();
		when(tossOrderClient.statusOf(any()))
			.thenReturn(TossOrderStatus.of(OrderStatus.PURCHASED.name(), ONE_TIME_SKU));
	}

	/**
	 * 🔴 같은 사용자가 같은 주문으로 동시에 몰려도 <b>원장은 한 행</b>이고 전원이 성공으로 답받는다.
	 * 여기서 원장이 두 행이 되면 같은 주문으로 이용권이 두 번 지급된다 = 무료 이용권 유출.
	 *
	 * <p>삽입에 진 요청은 예외로 끝나면 안 된다 — 클라이언트가 미결을 닫지 못해 복원 흐름이 깨진다.
	 * 진 쪽은 승자 행을 읽어 <b>같은 결과</b>(같은 상품 유형)로 수렴해야 한다.
	 */
	@Test
	@DisplayName("같은 주문에 동시 지급이 몰려도 원장은 한 행이고 전원이 같은 결과로 수렴한다")
	void 동시_지급_경쟁() throws InterruptedException {
		List<GrantResult> results = race(THREADS, i -> paymentPurchase.grant(OWNER, ORDER));

		assertThat(orderRepository.count())
			.as("두 행이 되면 같은 주문으로 이용권이 두 번 지급된다")
			.isEqualTo(1);
		assertThat(results).hasSize(THREADS)
			.allSatisfy(result -> {
				assertThat(result.granted()).isTrue();
				assertThat(result.productType()).isEqualTo(ProductType.CONSUMABLE);
			});
		assertThat(orderRepository.findByOrderId(ORDER).orElseThrow().getUserId()).isEqualTo(OWNER);
	}

	/**
	 * 🔴 <b>소유자는 선점으로 정해진다.</b> 주문 식별자를 아는 두 사용자가 동시에 요청하면 삽입에
	 * 이긴 쪽이 소유자가 되고, 진 쪽은 복구 경로에서 <b>거부</b>(PAY_005)된다 — 경쟁 상황에서도
	 * 주문 가로채기가 성립하면 안 된다.
	 */
	@Test
	@DisplayName("서로 다른 사용자가 같은 주문에 동시에 몰리면 한 명만 소유자가 되고 나머지는 거부된다")
	void 동시_선점_경쟁() throws InterruptedException {
		List<UserId> claimants = List.of(OWNER, OWNER, OWNER, OWNER, OTHER, OTHER, OTHER, OTHER);

		List<Object> outcomes = raceAllowingRejection(claimants);

		assertThat(orderRepository.count()).isEqualTo(1);
		UserId winner = orderRepository.findByOrderId(ORDER).orElseThrow().getUserId();
		assertThat(winner).isIn(OWNER, OTHER);

		long claimsByWinner = claimants.stream().filter(winner::equals).count();
		assertThat(outcomes.stream().filter(GrantResult.class::isInstance).count())
			.as("승자와 같은 사용자의 요청만 성공한다")
			.isEqualTo(claimsByWinner);
		assertThat(outcomes).filteredOn(BusinessException.class::isInstance)
			.hasSize(claimants.size() - (int) claimsByWinner)
			.allSatisfy(rejected -> assertThat(((BusinessException) rejected).getErrorCode())
				.isEqualTo(ErrorCode.PAY_005));
	}

	// ── helpers ────────────────────────────────────────────────────────

	/** 배리어로 동시에 풀어 놓는다 — {@code Thread.sleep} 금지(testing.md). 예외는 곧 유실이다. */
	private List<GrantResult> race(int threads, java.util.function.IntFunction<GrantResult> call)
			throws InterruptedException {
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<GrantResult>> futures = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int i = 0; i < threads; i++) {
				int index = i;
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return call.apply(index);
				}));
			}
		} finally {
			pool.shutdown();
		}
		awaitTermination(pool);

		List<GrantResult> results = new ArrayList<>();
		for (Future<GrantResult> future : futures) {
			try {
				results.add(future.get());
			} catch (ExecutionException e) {
				fail("동시 지급이 예외로 끝났다 — 그 요청은 미결을 닫지 못한다", e.getCause());
			}
		}
		return results;
	}

	/** 성공이면 {@link GrantResult}, 거부면 던져진 {@link BusinessException} 을 그대로 모은다. */
	private List<Object> raceAllowingRejection(List<UserId> claimants) throws InterruptedException {
		int threads = claimants.size();
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<Object>> futures = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (UserId claimant : claimants) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					try {
						return (Object) paymentPurchase.grant(claimant, ORDER);
					} catch (BusinessException rejected) {
						return rejected;
					}
				}));
			}
		} finally {
			pool.shutdown();
		}
		awaitTermination(pool);

		List<Object> outcomes = new ArrayList<>();
		for (Future<Object> future : futures) {
			try {
				outcomes.add(future.get());
			} catch (ExecutionException e) {
				fail("동시 선점 경쟁이 비즈니스 예외가 아닌 예외로 끝났다 — 경쟁 복구가 새고 있다", e.getCause());
			}
		}
		return outcomes;
	}

	private static void awaitTermination(ExecutorService pool) throws InterruptedException {
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("동시 지급이 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();
	}

	private static void awaitGate(CyclicBarrier gate) {
		try {
			gate.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		} catch (BrokenBarrierException | TimeoutException e) {
			throw new IllegalStateException(e);
		}
	}
}
