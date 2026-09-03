package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.auth.internal.service.support.AnonymousKeyHasher;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
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
@Import({JpaAuditingConfig.class, AuthConcurrencyTest.TestClockConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class AuthConcurrencyTest {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	private static final int THREADS = 16;

	@Autowired
	private AuthPort authPort;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private AnonymousKeyHasher hasher;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	@DisplayName("같은 익명키로 동시에 로그인해도 사용자는 정확히 하나이고 newUser=true 는 한 번뿐이다")
	void C1_동시_등록() throws Exception {
		String key = AnonymousKeyFixture.unique("race");

		List<LoginResult> results = raceLogin(key, THREADS);

		assertThat(results).hasSize(THREADS);
		assertThat(results).filteredOn(LoginResult::newUser).hasSize(1);
		assertThat(userRepository.count()).isEqualTo(1);

		// (v4) 각 호출이 자기 refresh 를 받는다 — 개수가 다르면 발급이 조용히 실패한 호출이 있다는 뜻이다
		assertThat(results).extracting(LoginResult::refreshToken).doesNotHaveDuplicates();
		assertThat(refreshTokenRepository.count()).isEqualTo(THREADS);
	}

	@Test
	@DisplayName("경쟁에서 진 호출도 예외 없이 승자와 같은 행의 등록 시각을 받는다")
	void C1_진_쪽도_정상_응답을_받는다() throws Exception {
		String key = AnonymousKeyFixture.unique("race-loser");

		List<LoginResult> results = raceLogin(key, THREADS);

		var row = userRepository.findByAnonymousKeyHash(hasher.hash(key)).orElseThrow();
		LocalDateTime rowCreatedAt = row.getCreatedAt();

		// (v3) auth-design §10 — 승자·패자 전원이 같은 행의 userId 를 받는다. 여기서 갈리면
		// 경쟁에서 진 쪽의 결제·이용권이 존재하지 않는 사용자에게 붙는다(payment-design §2-1 쟁점 1).
		assertThat(results)
			.allSatisfy(login ->
				assertThat(login.userId()).isEqualTo(row.getId()));

		// 진 쪽(newUser=false)은 ① 또는 ③에서 DB 행을 읽으므로 저장값과 정확히 같다.
		assertThat(results).filteredOn(login -> !login.newUser())
			.hasSize(THREADS - 1)
			.allSatisfy(login -> assertThat(login.registeredAt()).isEqualTo(rowCreatedAt));

		// 이긴 쪽은 영속화 직후의 인메모리 값이라 DB 저장 정밀도만큼만 차이 날 수 있다.
		LoginResult winner = results.stream().filter(LoginResult::newUser).findFirst().orElseThrow();
		assertThat(winner.registeredAt().truncatedTo(ChronoUnit.MILLIS))
			.isEqualTo(rowCreatedAt.truncatedTo(ChronoUnit.MILLIS));
	}

	@Test
	@DisplayName("이미 등록된 익명키로 동시에 호출하면 전부 newUser=false 이고 사용자 수도 그대로다")
	void C2_기존_키_동시_조회() throws Exception {
		String key = AnonymousKeyFixture.unique("revisit");
		assertThat(authPort.login(key).newUser()).isTrue();

		List<LoginResult> results = raceLogin(key, THREADS);

		assertThat(results).hasSize(THREADS);
		assertThat(results).noneMatch(LoginResult::newUser);
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("여러 익명키가 동시에 로그인해도 키마다 사용자는 정확히 하나다")
	void C1_여러_키_동시_등록() throws Exception {
		List<String> keys = List.of(
			AnonymousKeyFixture.unique("multi-race-1"),
			AnonymousKeyFixture.unique("multi-race-2"),
			AnonymousKeyFixture.unique("multi-race-3"),
			AnonymousKeyFixture.unique("multi-race-4"));

		for (String key : keys) {
			List<LoginResult> results = raceLogin(key, 8);
			assertThat(results).filteredOn(LoginResult::newUser)
				.as("익명키 %s", key)
				.hasSize(1);
		}

		assertThat(userRepository.count()).isEqualTo(keys.size());
	}

	@Test
	@DisplayName("동시 로그인 경쟁이 나도 운영 레벨(WARN 이상) 로그에 익명키 원문이 남지 않는다")
	void C1_경쟁_로그에_익명키가_남지_않는다() throws Exception {
		String key = AnonymousKeyFixture.unique("log-race");
		String hash = hasher.hash(key);
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
			org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
			new ch.qos.logback.core.read.ListAppender<>();
		appender.start();

		root.addAppender(appender);
		try {
			raceLogin(key, THREADS);
		} finally {
			root.detachAppender(appender);
			appender.stop();
		}

		List<String> productionLevel = appender.list.stream()
			.filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
			.map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
			.toList();

		assertThat(productionLevel)
			.as("auth.md §3 U6 · §4-5 — 익명키 원문은 로그 어디에도 남기지 않는다")
			.noneMatch(message -> message.contains(key));

		assertThat(productionLevel)
			.as("제약 위반 WARN 은 그대로 찍혀야 한다(값만 해시로 바뀐 것) — 안 보이면 이 테스트는 공허하다")
			.anyMatch(message -> message.contains(hash));
	}

	@Test
	@DisplayName("이 테스트는 트랜잭션 밖에서 login 을 부른다 — §6-4 전제를 스스로 지킨다")
	void 트랜잭션_밖에서_호출한다() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("login 을 트랜잭션 안에서 부르면 §6-2 함정 ④가 되살아나 경쟁이 재현되지 않는다")
			.isFalse();
	}

	private List<LoginResult> raceLogin(String key, int threads) throws InterruptedException {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CyclicBarrier gate = new CyclicBarrier(threads);
		List<Future<LoginResult>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					awaitGate(gate);
					return authPort.login(key);
				}));
			}
		} finally {
			pool.shutdown();
		}
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
			.as("동시 로그인이 60초 안에 끝나야 한다 — 직렬화·데드락이면 여기서 드러난다")
			.isTrue();

		List<LoginResult> results = new ArrayList<>();
		for (Future<LoginResult> future : futures) {
			try {
				results.add(future.get());
			} catch (ExecutionException e) {
				fail("동시 로그인이 예외로 끝났다 — auth-design.md §6-1 C1 은 '에러 응답 금지'다", e.getCause());
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
