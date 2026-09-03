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

@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class PaymentConcurrencyTest {

	private static final int THREADS = 8;

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";
	private static final OrderId ORDER = new OrderId(RAW);
	private static final UserId OWNER = new UserId(42L);
	private static final UserId OTHER = new UserId(99L);

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
