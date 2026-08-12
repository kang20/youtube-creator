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

/**
 * auth-design.md §6 동시성 — 불변식 "같은 익명키에 대응하는 User 는 정확히 하나"(U2 멱등).
 *
 * <p>덮는 것: §6-1 C1(동시 등록 경쟁) · C2(기존 키 동시 조회) · §6-4 판정 매트릭스 "경쟁에서 짐" 행 ·
 * §6-5(newUser 는 먼저 커밋한 쪽만 true).
 *
 * <p><b>(v4) 대상 메서드가 {@code register} → {@code login} 으로 바뀌었다</b>(auth-design.md §14-2 —
 * 등록 멱등·경쟁 흡수 로직은 §14-3 "registration = 기존 register 로직" 그대로다). 각 호출이
 * refresh 발급까지 하므로 경쟁의 결과로 <b>사용자는 1명, refresh 는 호출 수만큼</b> 생기는 것이 정상이다.
 *
 * <p>⚠️ <b>실제 멀티스레드로 재현한다.</b> 목으로 {@code DataIntegrityViolationException} 을 흉내 내면
 * §6-4 의 트랜잭션 경계(바깥 트랜잭션 없음 + 삽입만 REQUIRES_NEW)가 전혀 검증되지 않는다.
 *
 * <p>⚠️ <b>테스트에 {@code @Transactional} 을 붙이지 않는다.</b> {@code login}(등록부)은 트랜잭션 밖에서
 * 호출되어야 한다는 것이 §6-4 의 전제다. 트랜잭션 안에서 부르면 재조회(③)가 호출자의 스냅샷에 갇혀
 * §6-2 함정 ④가 되살아나고, 이 테스트는 목적과 정반대의 것을 검증하게 된다.
 *
 * <p>커넥션 풀을 스레드 수보다 크게 잡는 이유: 풀이 좁으면 스레드가 커넥션을 기다리며 직렬화되어
 * 경쟁 자체가 재현되지 않는다(테스트가 조용히 무의미해진다).
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, AuthConcurrencyTest.TestClockConfig.class})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=40")
class AuthConcurrencyTest {

	/** {@code TimeConfig} 를 직접 import 하면 Modulith 빈 선별기가 죽는다(AuthServiceTest javadoc). */
	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	/** 동시 호출 수. 배리어로 동시에 풀어 놓으므로 대부분이 ①에서 "없음"을 보고 ②로 몰린다. */
	private static final int THREADS = 16;

	@Autowired
	private AuthPort authPort;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	/** (v2) 저장·조회 키는 해시다(§3-2). 실제 빈을 그대로 써서 계약이 두 벌이 되지 않게 한다. */
	@Autowired
	private AnonymousKeyHasher hasher;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	/**
	 * §6-1 C1 — 부트스트랩 중복 호출(리트라이·더블탭·앱 복귀)로 실제로 일어나는 경쟁.
	 * 요구 결과: <b>사용자 1명 · newUser=true 정확히 1회 · 에러 응답 금지</b>.
	 * (v4) 전원이 각자의 토큰 쌍을 받는다 — 재로그인은 기존 refresh 를 폐기하지 않는다(auth.md §5-2).
	 */
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

	/**
	 * §6-4 판정 매트릭스 "경쟁에서 짐" 행 — <b>500 이 아니라 정상 200</b> 이고,
	 * 진 쪽은 화면상 "이미 등록된 사용자"와 구분되지 않는다. 즉 승자와 같은 행의 등록 시각을 받는다.
	 */
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

	/**
	 * §6-1 C2 — 기존 익명키로 동시 다발 로그인(정상 재방문). 등록 축엔 경쟁이 없어야 한다:
	 * 아무도 newUser=true 를 받지 않고 사용자 수도 늘지 않는다.
	 */
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

	/**
	 * 서로 다른 익명키가 섞여 동시에 들어와도 불변식은 키 단위로 유지된다(§6-1 불변식).
	 * 경쟁 창을 여러 키로 늘려 §6-4 ③(재조회) 경로가 확실히 실행되게 하는 역할도 한다.
	 */
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

	/**
	 * U6 · auth.md §4-5 — <b>경쟁이 나도 익명키 원문이 로그에 남으면 안 된다.</b>
	 *
	 * <p>§6-1 C1 은 이 경쟁이 "실제로 일어난다"고 못 박았다(부트스트랩은 진입 직후라 중복 호출이 가장 잦다).
	 * 즉 이 경로에서 찍히는 로그는 <b>운영 로그</b>다.
	 *
	 * <p>WARN 이상만 본다 — 운영은 {@code logging.level.root: INFO}(application-prod.yml)라
	 * 테스트 프로파일에만 켜진 SQL/바인딩 TRACE 는 운영에 나가지 않는다.
	 * <b>WARN 이상에 원문이 보이면 그건 운영 로그에도 그대로 보인다.</b>
	 *
	 * <p><b>(v2) 이 테스트는 §3-2(해시 저장)의 감시자다.</b> 라운드 2 에서는 여기가 빨갰다 —
	 * 원문을 저장하던 시절엔 UNIQUE 위반 WARN 이 원문을 그대로 실었다(blockers B4).
	 * 누가 {@code User.anonymousKeyHash} 를 원문으로 되돌리면 이 테스트가 먼저 빨개진다.
	 *
	 * <p>⚠️ <b>공허하게 통과하지 않도록 "해시는 로그에 있다"를 함께 단언한다.</b>
	 * 제약 위반 WARN 자체가 사라져도 "원문 없음"은 통과해 버리기 때문이다 —
	 * 해시가 보인다는 것은 <b>경쟁이 실제로 났고 WARN 도 그대로 찍혔는데 값만 바뀌었다</b>는 증거다.
	 * (해결책은 로그를 없애는 것이 아니라 로그가 싣는 값을 바꾸는 것이었다 — §6-4 v2)
	 */
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

	/**
	 * §6-4 전제 — {@code login} 은 트랜잭션 밖에서 호출된다.
	 * 이 테스트 클래스 자체가 그 전제를 지키고 있는지 고정한다(무심코 {@code @Transactional} 을 붙이면 실패).
	 */
	@Test
	@DisplayName("이 테스트는 트랜잭션 밖에서 login 을 부른다 — §6-4 전제를 스스로 지킨다")
	void 트랜잭션_밖에서_호출한다() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("login 을 트랜잭션 안에서 부르면 §6-2 함정 ④가 되살아나 경쟁이 재현되지 않는다")
			.isFalse();
	}

	/**
	 * 배리어로 모든 스레드를 같은 순간에 풀어 놓고 {@code login} 을 동시에 부른다.
	 * {@code Thread.sleep} 을 쓰지 않는다(docs/rule/testing.md).
	 *
	 * @return 각 호출의 결과. 하나라도 예외로 끝나면 그 자리에서 실패시킨다(§6-1 "에러 응답 금지")
	 */
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
