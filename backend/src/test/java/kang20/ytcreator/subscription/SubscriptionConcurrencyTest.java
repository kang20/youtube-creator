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

/**
 * S8·S5·S6 동시성 — 🔴 <b>순서 판정과 반영이 분리될 수 없다</b>는 규칙, 그리고 UNIQUE 심판
 * (payment.md 구독 규칙 "읽어서 비교한 뒤 쓰면 동시에 도착한 두 웹훅이 둘 다 통과한다").
 *
 * <p>⚠️ {@code @Transactional} 을 붙이지 않는다 — 붙이면 REQUIRES_NEW 삽입과 쓰기 빈의 트랜잭션이
 * 테스트 트랜잭션에 흡수돼 경쟁 자체가 사라진다.
 *
 * <p>🔴 <b>이 테스트가 고정하는 것은 결과가 아니라 구조다.</b> 웹훅 반영은 엔티티를 읽어서 비교한
 * 뒤 쓰는 형태인데, 그 읽기가 <b>행 잠금</b>({@code findByOrderIdForUpdate})이라야 성립한다.
 * {@code @Lock} 하나만 빠져도 순차 테스트는 전부 통과하고 여기서만 깨진다 — 그 회귀를 막는 것이
 * 존재 이유다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubscriptionConcurrencyTest.TestClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.subscription.webhook.secret=" + SubscriptionConcurrencyTest.SECRET,
	"spring.datasource.hikari.maximum-pool-size=40"
})
class SubscriptionConcurrencyTest {

	static final String SECRET = "test-webhook-shared-secret";

	/** 토스가 실제로 보내는 헤더 모양 — 설정값에 Basic 스킴이 붙는다. */
	private static final String AUTH_HEADER = "Basic " + SECRET;

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	/** 같은 순간에 풀 스레드 수 — 전원이 선판정에서 0행을 보게 만든다. */
	private static final int THREADS = 8;

	private static final UserId USER = new UserId(8001L);
	private static final OrderId ORDER = new OrderId("race-order");

	/** 웹훅 발생 시각의 기준점. i 번째 웹훅의 발생 시각은 {@code BASE + i분} 이다. */
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

	// ── S8 — 동시 도착 웹훅 ─────────────────────────────────────────────

	/**
	 * S8 — 🔴 <b>동시에 도착한 웹훅들의 최종 상태는 가장 나중 것으로 수렴한다.</b>
	 *
	 * <p>행 잠금이라서 성립한다 — 늦게 들어온 과거 웹훅은 앞선 트랜잭션의 커밋을 기다렸다가
	 * 갱신된 기준값을 다시 읽고 스스로 탈락한다. 잠금 없이 읽었다면 <b>둘 다 통과</b>해 마지막에
	 * 커밋한 쪽이 이기고, 만료가 과거로 되감겨 <b>돈을 낸 사람이 막힌다</b>.
	 *
	 * <p>발생 시각과 만료 시각을 한 쌍으로 묶어 보낸다 — 최종 행의 만료가 곧 "누가 이겼는가"다.
	 */
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

	/**
	 * S8 — 가장 나중 웹훅이 <b>먼저</b> 반영된 뒤에 과거 웹훅들이 몰려와도 결과가 같다.
	 * (앞 테스트는 순서가 운에 맡겨지지만, 이쪽은 "이미 최신이 반영된 상태"를 결정적으로 만든다.)
	 */
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

	// ── S16 — 재확인 ↔ 지각 웹훅 ───────────────────────────────────────

	/**
	 * S16 — 🔴 <b>재확인도 웹훅과 같은 행 잠금으로 읽어야 한다.</b> 한쪽만 잠그면 잠금이 성립하지
	 * 않는다 — 잠그지 않은 쪽이 옛 스냅샷을 들고 있다가 나중에 커밋하면 잠근 쪽의 갱신을 통째로
	 * 덮는다. {@code @Version} 도 없어 두 번째 방어선이 없다.
	 *
	 * <p>겹침이 드문 일이 아니다. <b>상태 미확인이란 늦은 웹훅이 아직 안 온 구간</b>이고, 재확인은
	 * 바로 그 구간에 사용자가 누르는 버튼이다 — 지각 웹훅의 도착과 겹치기 쉬운 조합이다.
	 *
	 * <p>어느 쪽이 먼저 커밋하든 결과는 하나여야 한다: 재확인이 먼저면 뒤이은 웹훅이 더 나중 발생분
	 * 이라 이기고, 웹훅이 먼저면 재확인이 잠금을 얻어 다시 읽었을 때 미확인이 아니라 무동작이다.
	 * 잠금이 빠지면 임시 보정이 정본을 덮고 {@code lastWebhookOccurredAt} 까지 되감긴다.
	 */
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

	// ── S6 — 동시 개시(같은 주문) ───────────────────────────────────────

	/**
	 * S6 — 같은 주문의 개시가 동시에 몰려도 <b>구독은 한 행</b>이고 <b>전원이 예외 없이</b> 끝난다.
	 *
	 * <p>선판정({@code findByOrderId})은 방어가 아니다 — 전원이 여기서 0행을 보고 삽입으로 몰린다.
	 * 불변식의 근거는 {@code UNIQUE(order_id)} 이고, 진 쪽은 승자 행으로 접혀야 한다. 여기서
	 * 예외가 나가면 리스너가 그 이벤트를 영원히 재시도한다.
	 */
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

	// ── S5 — 동시 개시(다른 주문, 같은 사용자) ──────────────────────────

	/**
	 * S5 — 같은 사용자가 <b>서로 다른 주문</b>으로 동시에 몰리면 <b>전부 열린다</b>
	 * (2026-08-16 결정 — {@code UNIQUE(user_id)} 제거).
	 *
	 * <p>주문이 다르면 계약도 다르다. 예전에는 {@code UNIQUE(user_id)} 로 하나만 남기고 나머지를
	 * {@code SUB_001} 로 거부했는데, 그 제약은 {@code status} 를 몰라서 실제로는 <b>재구독 자체를
	 * 영구히 막고</b> 있었다({@code Subscription} javadoc).
	 *
	 * <p>🔴 동시성이 사라진 게 아니다 — {@code UNIQUE(order_id)} 는 그대로이고 S6 이 그걸 본다.
	 * 여기서는 <b>사용자 축에는 애초에 경쟁이 없다</b>는 것을 고정한다.
	 */
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

	// ── helpers ────────────────────────────────────────────────────────

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

	/** 배리어로 동시에 풀어 놓는다 — {@code Thread.sleep} 금지(testing.md). 예외는 곧 유실이다. */
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
