package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import kang20.ytcreator.auth.dto.Registration;
import kang20.ytcreator.auth.internal.service.support.AnonymousKeyHasher;
import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * U2(사용자 등록·멱등) · U4(등록·식별의 모듈 공개 API).
 *
 * <p>근거: auth.md §3 U2·U4 · auth-design.md §5-1(서비스 흐름) · §6-4(판정 매트릭스 1·2행) · §10.
 *
 * <p>⚠️ <b>트랜잭션을 열지 않는다.</b> {@code @Transactional} 을 붙이면 auth-design.md §6-2 함정 ④가
 * 테스트에서 재현되어 설계 전제(호출자가 트랜잭션을 열지 않는다, §6-4)를 검증하지 못한다.
 *
 * <p>⚠️ <b>{@code registeredAt} 을 {@code Clock} 고정으로 단언하지 않는다</b>(blockers.md B3 ·
 * auth-design.md §12-4). {@code createdAt} 은 JPA Auditing 이 채우고 {@code TimeConfig} 의
 * {@code Clock} 빈을 보지 않으므로, <b>저장된 행의 createdAt 과의 상대 비교</b>로만 검증한다.
 *
 * <p>{@code JpaAuditingConfig} 를 명시적으로 import 하는 이유: 모듈 슬라이스는 {@code auth} 만
 * 부팅하므로 {@code config} 모듈의 {@code @EnableJpaAuditing} 이 올라오지 않는다. 그러면
 * {@code createdAt} 이 null 로 들어가 NOT NULL 위반이 나고, 그 예외가
 * {@code DataIntegrityViolationException} 이라 §6-4 의 경쟁 처리 분기로 잘못 흘러간다.
 * ({@code JpaAuditingConfig} 가 별도 클래스인 이유가 바로 "필요할 때만 import" 다.)
 *
 * <p><b>(v2) 저장 값은 익명키 원문이 아니라 해시다</b>(§3-2, blockers B4). 그래서 저장된 행을 찾을 때는
 * {@code findByAnonymousKeyHash(hasher.hash(원문))} 으로 찾는다 — 원문으로는 찾을 수 없고,
 * 찾을 수 있으면 그게 U6 위반이다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import(JpaAuditingConfig.class)
class AuthServiceTest {

	@Autowired
	private AuthPort authService;

	@Autowired
	private UserRepository userRepository;

	/** 실제 빈을 그대로 쓴다 — 테스트가 별도 해시 구현을 갖게 되면 계약이 두 벌이 된다. */
	@Autowired
	private AnonymousKeyHasher hasher;

	@BeforeEach
	void clean() {
		userRepository.deleteAll();
	}

	/** 저장된 행은 <b>해시로만</b> 찾을 수 있다(§3-2). */
	private User storedFor(String rawKey) {
		return userRepository.findByAnonymousKeyHash(hasher.hash(rawKey)).orElseThrow();
	}

	/** U2 · §6-4 판정 매트릭스 "최초 등록" — 조회 없음 → 삽입 → newUser=true */
	@Test
	@DisplayName("처음 보는 익명키는 사용자로 등록되고 newUser=true 다")
	void 최초_등록() {
		String key = AnonymousKeyFixture.unique("first");

		Registration registration = authService.register(key);

		assertThat(registration.newUser()).isTrue();
		assertThat(registration.registeredAt()).isNotNull();

		User stored = storedFor(key);
		assertThat(stored.getAnonymousKeyHash()).isEqualTo(hasher.hash(key));
		assertThat(userRepository.count()).isEqualTo(1);

		// (v3) auth-design §10 — userId 는 저장 행의 id 를 타입화한 값이다. 다른 행의 id 가 실리면
		// payment 의 모든 소유권(주문·잔량·구독)이 남의 것에 붙는다.
		assertThat(registration.userId()).isEqualTo(stored.getId());
	}

	/**
	 * U6 · auth-design.md §3-2 (v2, blockers B4) — <b>DB 에 익명키 원문이 존재하지 않는다.</b>
	 *
	 * <p>이것이 B4 해소의 뿌리다. 저장 값이 해시라서 UNIQUE 위반 메시지에도 해시만 실린다
	 * (그 결과는 {@code AuthConcurrencyTest} 의 로그 단언이 본다). 여기서는 <b>저장 자체</b>를 본다 —
	 * 컬럼에 원문이 다시 들어오는 변경은 그 자체로 U6 위반이므로 여기서 먼저 빨개져야 한다.
	 */
	@Test
	@DisplayName("저장되는 값은 익명키 원문이 아니라 SHA-256 해시다 — 원문으로는 조회조차 되지 않는다")
	void 저장_값에_익명키_원문이_없다() {
		String key = AnonymousKeyFixture.unique("no-raw");

		authService.register(key);

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

	/** U2 멱등 — auth.md §3 U2 "같은 익명키로 몇 번을 호출해도 사용자는 하나다" */
	@Test
	@DisplayName("같은 익명키로 여러 번 호출해도 사용자는 하나이고 두 번째부터 newUser=false 다")
	void 멱등하다() {
		String key = AnonymousKeyFixture.unique("idempotent");

		Registration first = authService.register(key);
		Registration second = authService.register(key);
		Registration third = authService.register(key);

		assertThat(first.newUser()).isTrue();
		assertThat(second.newUser()).isFalse();
		assertThat(third.newUser()).isFalse();
		assertThat(userRepository.count()).isEqualTo(1);

		// 멱등이므로 응답도 같다 — auth.md §5-2 "몇 번 호출해도 사용자는 하나이고 응답도 같다"
		assertThat(second.registeredAt()).isEqualTo(third.registeredAt());

		// (v3) auth-design §10 — 멱등은 userId 축에도 적용된다. 재호출이 다른 id 를 주면
		// payment 가 같은 사용자를 두 명으로 본다.
		assertThat(second.userId()).isEqualTo(first.userId());
		assertThat(third.userId()).isEqualTo(first.userId());
	}

	/**
	 * auth.md §5-2 — {@code registeredAt} 은 "이 사용자가 처음 등록된 시각"이고
	 * auth-design.md §3 이 그 값을 {@code BaseTimeEntity.createdAt} 으로 확정했다.
	 * B3 대로 <b>절대값이 아니라 저장된 행과의 상대 비교</b>로 본다.
	 */
	@Test
	@DisplayName("registeredAt 은 저장된 행의 createdAt 이다 — 별도 시각을 만들어 내지 않는다")
	void registeredAt_은_행의_createdAt_이다() {
		String key = AnonymousKeyFixture.unique("registered-at");

		Registration created = authService.register(key);
		LocalDateTime storedCreatedAt = storedFor(key).getCreatedAt();

		// 삽입 응답은 영속화 직후의 인메모리 값이라 DB 저장 정밀도(TIMESTAMP(6))만큼 차이가 날 수 있다.
		assertThat(created.registeredAt().truncatedTo(ChronoUnit.MILLIS))
			.isEqualTo(storedCreatedAt.truncatedTo(ChronoUnit.MILLIS));

		// 재호출은 DB 에서 읽은 값을 그대로 돌려주므로 정확히 같아야 한다.
		Registration reread = authService.register(key);
		assertThat(reread.registeredAt()).isEqualTo(storedCreatedAt);
	}

	/** U2 — 익명키가 다르면 다른 사용자다. 멱등은 "익명키당" 이다(auth-design.md §3 UNIQUE) */
	@Test
	@DisplayName("익명키가 다르면 각각 별도의 사용자로 등록된다")
	void 익명키가_다르면_사용자도_다르다() {
		String one = AnonymousKeyFixture.unique("multi-a");
		String two = AnonymousKeyFixture.unique("multi-b");

		Registration first = authService.register(one);
		Registration second = authService.register(two);

		assertThat(first.newUser()).isTrue();
		assertThat(second.newUser()).isTrue();
		assertThat(userRepository.count()).isEqualTo(2);
		assertThat(userRepository.findByAnonymousKeyHash(hasher.hash(one))).isPresent();
		assertThat(userRepository.findByAnonymousKeyHash(hasher.hash(two))).isPresent();
	}

	/**
	 * U5 · auth-design.md §3-2 — 형식 검증을 통과한 <b>상한 길이</b> 원문도 끝까지 등록된다.
	 *
	 * <p>(v2) 저장되는 것은 길이 64 해시라 <b>원문 길이가 컬럼을 넘길 일이 없다.</b>
	 * §12-2 가 해소된 것이 이 지점이다 — 입력이 아무리 길어도 저장 길이는 고정이다.
	 */
	@Test
	@DisplayName("상한 길이 익명키도 등록되고, 저장 길이는 원문 길이와 무관하게 64다")
	void 상한_길이_익명키도_등록된다() {
		String key = AnonymousKeyFixture.atMaxLength();

		Registration registration = authService.register(key);

		assertThat(registration.newUser()).isTrue();
		User stored = storedFor(key);
		assertThat(stored.getAnonymousKeyHash())
			.hasSize(64)
			.isNotEqualTo(key);
		assertThat(key.length()).isNotEqualTo(stored.getAnonymousKeyHash().length());
	}

	/**
	 * U4 — 등록·식별은 <b>모듈 공개 API</b> 로 제공된다(auth.md §3 U4 · §5-3).
	 * 집계 모듈 {@code bootstrap} 과 payment 컨트롤러가 이 시그니처로 익명키를 해석한다
	 * (auth-design v3 §4 · payment-design.md §2-1 쟁점 1).
	 *
	 * <p>(v3) v2 의 "PK 를 담지 않는다"가 번복됐다 — 원시 Long 이 아니라 <b>타입화한 {@code UserId}</b> 로
	 * 담는다. 필드가 넷으로 늘거나 {@code userId} 가 원시 타입으로 바뀌면 여기서 먼저 빨개져야 한다.
	 */
	@Test
	@DisplayName("Registration 은 newUser·registeredAt·userId 세 필드이고 userId 는 저장 행의 id 다")
	void 모듈_공개_API_계약() {
		String key = AnonymousKeyFixture.unique("public-api");

		Registration registration = authService.register(key);

		// auth-design v3 §4 — record(newUser, registeredAt, userId). HTTP 응답에는 userId 를 싣지
		// 않지만(payment-design §2-2) 모듈 계약에는 담는다.
		assertThat(Registration.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("newUser", "registeredAt", "userId");
		assertThat(Registration.class.getRecordComponents()[2].getType())
			.as("원시 Long 로 되돌리면 어느 도메인의 Long 인지 시그니처에서 사라진다(§2-1 쟁점 1)")
			.isEqualTo(UserId.class);
		assertThat(registration.newUser()).isTrue();
		assertThat(registration.registeredAt()).isNotNull();

		// (v3) auth-design §10 — userId == 저장 행의 id
		assertThat(registration.userId()).isEqualTo(storedFor(key).getId());
	}
}
