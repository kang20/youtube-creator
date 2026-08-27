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

/**
 * C1·C2·C5 — {@code CreditGrantPort.grant} 의 증가 알고리즘
 * (new-domain/payment.md [횟수권 애그리거트] · 사용자 확정 결정 4).
 *
 * <p>이벤트 경유가 아니라 <b>포트를 직접 부른다</b> — 발행·수신 결합은 {@link CreditGrantFlowTest}
 * 가 본다. 여기서는 ① 원자 UPDATE → ② 첫 행 삽입(REQUIRES_NEW) → ③ UNIQUE 경쟁 복구의
 * 세 경로를 실제 DB(H2) 로 검증한다 — 경쟁 심판이 {@code UNIQUE(user_id)} 이므로 저장소를
 * 목으로 바꾸면 검증 대상이 사라진다.
 *
 * <p>⚠️ 테스트 클래스에 {@code @Transactional} 을 붙이지 않는다 — 붙이면 REQUIRES_NEW 삽입과
 * 경쟁 복구 UPDATE 가 테스트 트랜잭션에 흡수·격리되어 설계 전제를 검증하지 못한다.
 * 대신 운영 호출자({@code @ApplicationModuleListener} = REQUIRES_NEW 트랜잭션)를
 * {@link TransactionTemplate} 로 호출 단위마다 재현한다 — 포트 계약("트랜잭션 안에서 불러야
 * 한다", {@code CreditGrantPort} javadoc)이 그 근거다.
 *
 * <p>시간은 {@code Clock.fixed} 로 고정한다(testing.md "시간·랜덤은 주입") — 증가 UPDATE 가
 * {@code updated_at} 을 직접 갱신하므로 그 값을 고정 시각으로 단언할 수 있다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, CreditServiceTest.TestClockConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class CreditServiceTest {

	/** 증가 UPDATE 가 기록해야 하는 고정 현재 시각. */
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	/** {@code TimeConfig} 를 직접 import 하면 Modulith 빈 선별기가 죽는다(AuthServiceTest javadoc 실측). */
	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	/** 동시 첫 지급 호출 수 — 배리어로 같은 순간에 풀어 ①에서 전원이 0행을 보게 만든다. */
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

	// ── C1 — 첫 지급 ────────────────────────────────────────────────────

	/** C1 — 잔량 행이 없으면 1 로 생성된다 (payment.md "static create(): 첫 지급 때 잔량 행을 연다") */
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

	// ── C2 — 증가 ───────────────────────────────────────────────────────

	/**
	 * C2 — 행이 있으면 +1 (payment.md "plus(): 잔량을 1 증가한다" — 구현은 원자 UPDATE).
	 * 증가 경로는 {@code updated_at} 을 주입된 Clock 시각으로 직접 갱신한다(벌크 UPDATE 는
	 * Auditing 을 안 탄다 — 개발 보고 세부 결정).
	 */
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

	/** C1·C2 경계 — "잔량은 사용자당 한 행"이다. 다른 사용자의 지급이 내 잔량을 건드리면 안 된다 */
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

	// ── C5 — 동시 첫 지급 경쟁 ──────────────────────────────────────────

	/**
	 * C5 — 같은 사용자의 CONSUMABLE 지급이 동시에 몰려도(첫 지급 경쟁) {@code UNIQUE(user_id)}
	 * 심판 + UniqueRace 복구(승자 행에 원자 UPDATE 합류)로 <b>한 행, 잔량 = 호출 수</b>로
	 * 수렴한다 (payment.md "증감은 동시성 제어를 신경써야한다" · 사용자 확정 결정 4-③).
	 *
	 * <p>배리어로 전원을 같은 순간에 풀어 대부분이 ①에서 0행을 보고 ②(삽입)로 몰리게 한다 —
	 * 삽입에 진 쪽이 ③ 복구 경로를 실제로 탄다. 하나라도 예외로 끝나면 잔량이 새는 것이므로
	 * 그 자리에서 실패시킨다.
	 */
	@Test
	@DisplayName("동시 첫 지급 경쟁 — 한 행으로 수렴하고 잔량은 호출 수와 같다")
	void 동시_첫_지급_경쟁() throws InterruptedException {
		UserId user = new UserId(105L);

		race(user, THREADS);

		CreditBalance row = theOnlyRow();
		assertThat(row.getUserId()).isEqualTo(user);
		assertThat(row.getBalance()).isEqualTo(new Balance((long) THREADS));
	}

	// ── helpers ────────────────────────────────────────────────────────

	/**
	 * 운영 호출자(리스너)의 트랜잭션 경계를 재현한다 — {@code @ApplicationModuleListener} 가
	 * REQUIRES_NEW 를 열고 그 안에서 포트를 부른다.
	 */
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

	/** 배리어로 동시에 풀어 놓는다 — {@code Thread.sleep} 금지(testing.md). */
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
