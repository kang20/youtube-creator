package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.auth.internal.service.support.AnonymousKeyHasher;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
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

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, AuthServiceTest.TestClockConfig.class})
class AuthServiceTest {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	@Autowired
	private AuthPort authPort;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private AnonymousKeyHasher hasher;

	@Autowired
	private JwtSupport jwtSupport;

	@BeforeEach
	void clean() {
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	private User storedFor(String rawKey) {
		return userRepository.findByAnonymousKeyHash(hasher.hash(rawKey)).orElseThrow();
	}

	@Test
	@DisplayName("처음 보는 익명키는 사용자로 등록되고 newUser=true 다")
	void 최초_등록() {
		String key = AnonymousKeyFixture.unique("first");

		LoginResult login = authPort.login(key);

		assertThat(login.newUser()).isTrue();
		assertThat(login.registeredAt()).isNotNull();

		User stored = storedFor(key);
		assertThat(stored.getAnonymousKeyHash()).isEqualTo(hasher.hash(key));
		assertThat(userRepository.count()).isEqualTo(1);

		// (v3) auth-design §10 — userId 는 저장 행의 id 를 타입화한 값이다. 다른 행의 id 가 실리면
		// payment 의 모든 소유권(주문·잔량·구독)이 남의 것에 붙는다.
		assertThat(login.userId()).isEqualTo(stored.getId());
	}

	@Test
	@DisplayName("login 은 access·refresh 를 발급하고 access 의 주체는 그 사용자다")
	void U7_토큰_발급() {
		String key = AnonymousKeyFixture.unique("issue");

		LoginResult login = authPort.login(key);

		assertThat(login.accessToken()).isNotBlank();
		assertThat(login.refreshToken()).isNotBlank();

		// 발급과 같은 JwtSupport 빈으로 파싱 — 서명·클레임 라운드트립이 사용자를 되돌려야 한다
		assertThat(jwtSupport.parse(login.accessToken()).userId())
			.as("auth.md §5-1 — sub 는 userId 다. 어긋나면 U8 게이트가 남의 신원을 준다")
			.isEqualTo(login.userId());
	}

	@Test
	@DisplayName("저장되는 값은 익명키 원문이 아니라 SHA-256 해시다 — 원문으로는 조회조차 되지 않는다")
	void 저장_값에_익명키_원문이_없다() {
		String key = AnonymousKeyFixture.unique("no-raw");

		authPort.login(key);

		User stored = storedFor(key);
		assertThat(stored.getAnonymousKeyHash())
			.as("§3-2 — 원문을 저장하면 UNIQUE 위반 메시지로 로그에 샌다")
			.isNotEqualTo(key)
			.doesNotContain(key)
			.hasSize(64)
			.matches("[0-9a-f]{64}");

		assertThat(userRepository.findByAnonymousKeyHash(key))
			.as("원문으로 찾히면 원문이 저장돼 있다는 뜻이다")
			.isEmpty();
	}

	@Test
	@DisplayName("같은 익명키로 여러 번 호출해도 사용자는 하나이고, 토큰만 호출마다 새로 발급된다")
	void 멱등하다() {
		String key = AnonymousKeyFixture.unique("idempotent");

		LoginResult first = authPort.login(key);
		LoginResult second = authPort.login(key);
		LoginResult third = authPort.login(key);

		assertThat(first.newUser()).isTrue();
		assertThat(second.newUser()).isFalse();
		assertThat(third.newUser()).isFalse();
		assertThat(userRepository.count()).isEqualTo(1);

		// 멱등이므로 등록 축 응답도 같다 — auth.md §5-2 "몇 번 호출해도 사용자는 하나"
		assertThat(second.registeredAt()).isEqualTo(third.registeredAt());

		// (v3) auth-design §10 — 멱등은 userId 축에도 적용된다. 재호출이 다른 id 를 주면
		// payment 가 같은 사용자를 두 명으로 본다.
		assertThat(second.userId()).isEqualTo(first.userId());
		assertThat(third.userId()).isEqualTo(first.userId());

		// (v4) auth.md §5-2 — refresh 는 호출마다 새 값이고(불투명 랜덤) 기존 것을 폐기하지 않는다.
		// 세 번 로그인 = 세 기기가 각자의 refresh 를 갖는 정상 상황과 같아야 한다.
		assertThat(java.util.Set.of(first.refreshToken(), second.refreshToken(), third.refreshToken()))
			.hasSize(3);
		assertThat(refreshTokenRepository.findAll())
			.hasSize(3)
			.allSatisfy(token -> assertThat(token.isRevoked())
				.as("재로그인이 기존 refresh 를 폐기하면 다기기 사용이 즉시 깨진다(auth.md §5-2)")
				.isFalse());
	}

	@Test
	@DisplayName("registeredAt 은 저장된 행의 createdAt 이다 — 별도 시각을 만들어 내지 않는다")
	void registeredAt_은_행의_createdAt_이다() {
		String key = AnonymousKeyFixture.unique("registered-at");

		LoginResult created = authPort.login(key);
		LocalDateTime storedCreatedAt = storedFor(key).getCreatedAt();

		// 삽입 응답은 영속화 직후의 인메모리 값이라 DB 저장 정밀도(TIMESTAMP(6))만큼 차이가 날 수 있다.
		assertThat(created.registeredAt().truncatedTo(ChronoUnit.MILLIS))
			.isEqualTo(storedCreatedAt.truncatedTo(ChronoUnit.MILLIS));

		// 재호출은 DB 에서 읽은 값을 그대로 돌려주므로 정확히 같아야 한다.
		LoginResult reread = authPort.login(key);
		assertThat(reread.registeredAt()).isEqualTo(storedCreatedAt);
	}

	@Test
	@DisplayName("익명키가 다르면 각각 별도의 사용자로 등록된다")
	void 익명키가_다르면_사용자도_다르다() {
		String one = AnonymousKeyFixture.unique("multi-a");
		String two = AnonymousKeyFixture.unique("multi-b");

		LoginResult first = authPort.login(one);
		LoginResult second = authPort.login(two);

		assertThat(first.newUser()).isTrue();
		assertThat(second.newUser()).isTrue();
		assertThat(first.userId()).isNotEqualTo(second.userId());
		assertThat(userRepository.count()).isEqualTo(2);
		assertThat(userRepository.findByAnonymousKeyHash(hasher.hash(one))).isPresent();
		assertThat(userRepository.findByAnonymousKeyHash(hasher.hash(two))).isPresent();
	}

	@Test
	@DisplayName("상한 길이 익명키도 등록되고, 저장 길이는 원문 길이와 무관하게 64다")
	void 상한_길이_익명키도_등록된다() {
		String key = AnonymousKeyFixture.atMaxLength();

		LoginResult login = authPort.login(key);

		assertThat(login.newUser()).isTrue();
		User stored = storedFor(key);
		assertThat(stored.getAnonymousKeyHash())
			.hasSize(64)
			.isNotEqualTo(key);
		assertThat(key.length()).isNotEqualTo(stored.getAnonymousKeyHash().length());
	}

	@Test
	@DisplayName("LoginResult 는 newUser·registeredAt·userId·accessToken·refreshToken 이고 userId 는 저장 행의 id 다")
	void 모듈_공개_API_계약() {
		String key = AnonymousKeyFixture.unique("public-api");

		LoginResult login = authPort.login(key);

		// auth-design §14-2 — record(newUser, registeredAt, userId, accessToken, refreshToken).
		// HTTP 응답에는 userId 를 싣지 않지만(auth.md §5-2) 모듈 계약에는 담는다.
		assertThat(LoginResult.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("newUser", "registeredAt", "userId", "accessToken", "refreshToken");
		assertThat(LoginResult.class.getRecordComponents()[2].getType())
			.as("원시 Long 로 되돌리면 어느 도메인의 Long 인지 시그니처에서 사라진다(§2-1 쟁점 1)")
			.isEqualTo(UserId.class);
		assertThat(login.newUser()).isTrue();
		assertThat(login.registeredAt()).isNotNull();

		// (v3) auth-design §10 — userId == 저장 행의 id
		assertThat(login.userId()).isEqualTo(storedFor(key).getId());
	}
}
