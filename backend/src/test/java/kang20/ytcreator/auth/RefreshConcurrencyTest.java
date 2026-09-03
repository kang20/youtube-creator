package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.dto.TokenPair;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, RefreshConcurrencyTest.TestClockConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class RefreshConcurrencyTest {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	private static final int THREADS = 8;

	@Autowired
	private AuthPort authPort;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private kang20.ytcreator.auth.internal.service.support.RefreshTokenWriter refreshTokenWriter;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("같은 refresh 동시 N회 제출은 정확히 한 번만 성공하고 나머지는 전부 AUTH_005 다")
	void 같은_refresh_동시_제출은_한쪽만_성공한다() throws Exception {
		LoginResult login = authPort.login(AnonymousKeyFixture.unique("refresh-race"));
		String contested = login.refreshToken();

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CyclicBarrier gate = new CyclicBarrier(THREADS);
		List<Future<TokenPair>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < THREADS; i++) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return authPort.refresh(contested);
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("동시 갱신이 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();

		List<TokenPair> winners = new ArrayList<>();
		int losers = 0;
		for (Future<TokenPair> future : futures) {
			try {
				winners.add(future.get());
			} catch (ExecutionException e) {
				assertThat(e.getCause())
					.as("패자의 실패는 전부 AUTH_005 여야 한다(auth.md §5-5) — 다른 예외는 판정 코드 밖의 사고다")
					.isInstanceOf(BusinessException.class)
					.extracting(cause -> ((BusinessException) cause).getErrorCode())
					.isEqualTo(ErrorCode.AUTH_005);
				losers++;
			}
		}

		assertThat(winners)
			.as("auth.md §5-5 — 같은 refresh 의 동시 제출은 '한쪽만 성공'이다. 둘이면 탈취자와 사용자가"
				+ " 모두 유효 토큰을 갖는다")
			.hasSize(1);
		assertThat(losers).isEqualTo(THREADS - 1);

		// 원본(경쟁 대상)은 반드시 폐기돼 있다 — 승자의 회전이 지운 것
		assertThat(refreshTokenRepository.findByTokenHash(refreshTokenWriter.hash(contested)))
			.hasValueSatisfying(row -> assertThat(row.isRevoked()).isTrue());
		assertThat(refreshTokenRepository.findAll())
			.filteredOn(token -> !token.isRevoked())
			.as("살아남는 활성 토큰은 많아야 승자의 새 refresh 하나다(§14-5 — 재사용 오검지 시 0)")
			.hasSizeLessThanOrEqualTo(1);

		// 승자가 받은 새 쌍은 완전한 토큰이다
		TokenPair winner = winners.get(0);
		assertThat(winner.accessToken()).isNotBlank();
		assertThat(winner.refreshToken()).isNotBlank().isNotEqualTo(contested);
	}

	@Test
	@DisplayName("이 테스트는 트랜잭션 밖에서 refresh 를 부른다")
	void 트랜잭션_밖에서_호출한다() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
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
