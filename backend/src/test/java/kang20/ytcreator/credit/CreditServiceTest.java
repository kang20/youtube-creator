package kang20.ytcreator.credit;

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

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.credit.internal.entity.Balance;
import kang20.ytcreator.credit.internal.entity.CreditBalance;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.credit.internal.port.CreditGrantPort;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, CreditServiceTest.TestClockConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class CreditServiceTest {

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	private static final int THREADS = 16;

	@Autowired
	private CreditGrantPort creditGrant;

	@Autowired
	private CreditBalanceRepository creditBalanceRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate listenerTx;

	@BeforeEach
	void 잔량을_비운다() {
		listenerTx = new TransactionTemplate(transactionManager);
		creditBalanceRepository.deleteAll();
	}

	@Test
	@DisplayName("첫 지급 — 잔량 행이 없으면 1 로 생긴다")
	void 첫_지급() {
		UserId user = new UserId(101L);

		grantInListenerTx(user);

		CreditBalance row = theOnlyRow();
		assertThat(row.getId()).isNotNull();
		assertThat(row.getUserId()).isEqualTo(user);
		assertThat(row.getBalance()).isEqualTo(new Balance(1L));
	}

	@Test
	@DisplayName("잔량 행이 있으면 1 증가하고 updated_at 이 주입된 시계로 갱신된다")
	void 재지급_증가() {
		UserId user = new UserId(102L);
		grantInListenerTx(user);

		grantInListenerTx(user);

		CreditBalance row = theOnlyRow();
		assertThat(row.getBalance()).isEqualTo(new Balance(2L));
		assertThat(row.getUpdatedAt()).isEqualTo(FIXED_NOW);
	}

	@Test
	@DisplayName("잔량은 사용자당 한 행이고 서로 섞이지 않는다")
	void 사용자별_분리() {
		UserId first = new UserId(103L);
		UserId second = new UserId(104L);

		grantInListenerTx(first);
		grantInListenerTx(first);
		grantInListenerTx(second);

		assertThat(creditBalanceRepository.findAll()).hasSize(2);
		assertThat(balanceOf(first)).isEqualTo(new Balance(2L));
		assertThat(balanceOf(second)).isEqualTo(new Balance(1L));
	}

	@Test
	@DisplayName("동시 첫 지급 경쟁 — 한 행으로 수렴하고 잔량은 호출 수와 같다")
	void 동시_첫_지급_경쟁() throws InterruptedException {
		UserId user = new UserId(105L);

		race(user, THREADS);

		CreditBalance row = theOnlyRow();
		assertThat(row.getUserId()).isEqualTo(user);
		assertThat(row.getBalance()).isEqualTo(new Balance((long) THREADS));
	}

	private void grantInListenerTx(UserId userId) {
		listenerTx.executeWithoutResult(status -> creditGrant.grant(userId));
	}

	private CreditBalance theOnlyRow() {
		List<CreditBalance> rows = creditBalanceRepository.findAll();
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private Balance balanceOf(UserId userId) {
		return creditBalanceRepository.findAll().stream()
			.filter(row -> row.getUserId().equals(userId))
			.findFirst()
			.orElseThrow()
			.getBalance();
	}

	private void race(UserId user, int threads) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<?>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					grantInListenerTx(user);
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("동시 지급이 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();

		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (ExecutionException e) {
				fail("동시 지급이 예외로 끝났다 — 그 호출의 잔량 +1 이 유실된다(C5)", e.getCause());
			}
		}
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
