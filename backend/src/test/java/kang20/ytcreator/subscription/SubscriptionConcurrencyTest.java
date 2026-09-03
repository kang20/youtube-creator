package kang20.ytcreator.subscription;

import static kang20.ytcreator.subscription.SubscriptionFixture.snapshot;
import static kang20.ytcreator.subscription.SubscriptionFixture.statusChanged;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.ACTIVE;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.EXPIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Clock;
import java.time.LocalDateTime;
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
import java.util.function.IntFunction;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.subscription.internal.port.SubscriptionGrantPort;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubscriptionConcurrencyTest.TestClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.subscription.webhook.secret=" + SubscriptionConcurrencyTest.SECRET,
	"spring.datasource.hikari.maximum-pool-size=40"
})
class SubscriptionConcurrencyTest {

	static final String SECRET = "test-webhook-shared-secret";

	private static final String AUTH_HEADER = "Basic " + SECRET;

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	private static final int THREADS = 8;

	private static final UserId USER = new UserId(8001L);
	private static final OrderId ORDER = new OrderId("race-order");

	private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

	@Autowired
	private SubscriptionGrantPort subscriptionGrant;

	@Autowired
	private SubscriptionStatusPort subscriptionStatus;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@BeforeEach
	void 구독을_비운다() {
		subscriptionRepository.deleteAll();
	}

	@Test
	@DisplayName("S8 — 여러 웹훅이 동시에 도착해도 최종 상태는 가장 나중 발생분으로 수렴한다")
	void 동시_도착_웹훅_수렴() throws InterruptedException {
		subscriptionGrant.start(USER, ORDER);

		race(THREADS, index -> {
			subscriptionStatus.handleWebhook(AUTH_HEADER, statusChanged(ORDER.raw(), occurredAt(index),
				null, snapshot(ACTIVE, expiresAt(index), true)));
			return null;
		});

		Subscription converged = theOnlyRow();
		assertThat(converged.getLastWebhookOccurredAt())
			.as("🔴 기준값이 최대가 아니면 과거 웹훅이 나중 웹훅을 덮어썼다는 뜻이다")
			.isEqualTo(occurredAt(THREADS - 1));
		assertThat(converged.getExpiresAt())
			.as("🔴 만료가 되감기면 갱신을 받은 사용자가 막힌다")
			.isEqualTo(expiresAt(THREADS - 1));
	}

	@Test
	@DisplayName("S8 — 최신 웹훅이 먼저 반영된 뒤 과거 웹훅이 동시에 몰려와도 되감기지 않는다")
	void 이미_최신인데_과거가_몰려와도_되감기지_않는다() throws InterruptedException {
		subscriptionGrant.start(USER, ORDER);
		subscriptionStatus.handleWebhook(AUTH_HEADER, statusChanged(ORDER.raw(), occurredAt(THREADS - 1),
			null, snapshot(ACTIVE, expiresAt(THREADS - 1), true)));

		race(THREADS - 1, index -> {
			subscriptionStatus.handleWebhook(AUTH_HEADER, statusChanged(ORDER.raw(), occurredAt(index),
				null, snapshot(ACTIVE, expiresAt(index), true)));
			return null;
		});

		Subscription row = theOnlyRow();
		assertThat(row.getLastWebhookOccurredAt()).isEqualTo(occurredAt(THREADS - 1));
		assertThat(row.getExpiresAt()).isEqualTo(expiresAt(THREADS - 1));
	}

	@Test
	@DisplayName("S16 — 재확인과 지각 웹훅이 동시에 와도 정본(웹훅)이 이긴다")
	void 재확인과_지각_웹훅() throws InterruptedException {
		subscriptionGrant.start(USER, ORDER);
		subscriptionStatus.handleWebhook(AUTH_HEADER, statusChanged(ORDER.raw(), occurredAt(0),
			null, snapshot(ACTIVE, FIXED_NOW.minusDays(10), true)));

		race(2, index -> {
			if (index == 0) {
				subscriptionStatus.recheck(USER,
					new SubscriptionSnapshot(ORDER.raw(), ACTIVE.name(), FIXED_NOW.plusDays(30), true));
			} else {
				subscriptionStatus.handleWebhook(AUTH_HEADER, statusChanged(ORDER.raw(), occurredAt(1),
					null, snapshot(EXPIRED, FIXED_NOW.minusDays(10), false)));
			}
			return null;
		});

		Subscription row = theOnlyRow();
		assertThat(row.getStatus())
			.as("🔴 임시 보정이 정본을 덮으면 만료된 구독이 활성으로 되살아난다").isEqualTo(EXPIRED);
		assertThat(row.getLastWebhookOccurredAt())
			.as("🔴 기준값이 되감기면 그 뒤 웹훅이 과거로 버려지고 클라이언트 값이 영구히 정본이 된다")
			.isEqualTo(occurredAt(1));
	}

	@Test
	@DisplayName("S6 — 같은 주문의 동시 개시는 한 행으로 수렴하고 아무도 예외로 끝나지 않는다")
	void 동시_개시_같은_주문() throws InterruptedException {
		race(THREADS, index -> {
			subscriptionGrant.start(USER, ORDER);
			return null;
		});

		assertThat(subscriptionRepository.count())
			.as("두 행이 되면 한 사용자가 활성 구독 두 개를 갖는다").isEqualTo(1);
		assertThat(theOnlyRow().getOrderId()).isEqualTo(ORDER);
	}

	@Test
	@DisplayName("S5 — 같은 사용자가 다른 주문으로 동시에 몰리면 전부 열린다 — 주문이 다르면 계약도 다르다")
	void 동시_개시_다른_주문() throws InterruptedException {
		race(THREADS, index -> {
			subscriptionGrant.start(USER, new OrderId("race-distinct-" + index));
			return null;
		});

		assertThat(subscriptionRepository.count())
			.as("주문마다 계약 하나 — 거부되면 재구독이 막힌 것이다").isEqualTo(THREADS);
	}

	private static LocalDateTime occurredAt(int index) {
		return BASE.plusMinutes(index);
	}

	private static LocalDateTime expiresAt(int index) {
		return BASE.plusDays(30L + index);
	}

	private Subscription theOnlyRow() {
		List<Subscription> rows = subscriptionRepository.findAll();
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private void race(int threads, IntFunction<Void> call) throws InterruptedException {
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<Void>> futures = new ArrayList<>();
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

		for (Future<Void> future : futures) {
			try {
				future.get();
			} catch (ExecutionException e) {
				fail("동시 호출이 예외로 끝났다 — 그 호출은 유실이다", e.getCause());
			}
		}
	}

	private static void awaitTermination(ExecutorService pool) throws InterruptedException {
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
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
