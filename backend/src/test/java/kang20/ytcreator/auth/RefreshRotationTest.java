package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.auth.dto.TokenPair;
import kang20.ytcreator.auth.internal.entity.RefreshToken;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import kang20.ytcreator.auth.internal.handler.outbound.repository.UserRepository;
import kang20.ytcreator.auth.internal.service.support.JwtSupport;
import kang20.ytcreator.auth.internal.service.support.RefreshTokenWriter;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
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

/**
 * U9(갱신·회전·재사용 감지) · U10(refresh 비노출 저장) — auth.md §5-5 · auth-design.md §14-3·§14-5.
 *
 * <p>시간이 얽히는 도메인이라 {@code Clock} 을 {@link MutableClock} 으로 갈아끼운다
 * (docs/rule/testing.md "시간·랜덤은 주입") — 만료 14일을 실제로 기다리지 않고 시계를 옮긴다.
 *
 * <p>⚠️ 트랜잭션을 열지 않는다 — {@code refresh} 는 무TX 가 전제다(§14-3 · AuthTransactionBoundaryTest).
 *
 * <p>만료 경계는 {@code expiresAt <= now} = 만료다(round-1-dev.md 판단 8 —
 * {@code RefreshToken.isExpired} javadoc 확정).
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, RefreshRotationTest.TestClockConfig.class})
class RefreshRotationTest {

	/** 고정 기준 시각 — 초 단위 정밀도로 잡아 만료 경계 계산에 반올림 여지를 없앤다. */
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 12, 12, 0, 0);

	private static final MutableClock CLOCK = new MutableClock(BASE);

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return CLOCK;
		}
	}

	@Autowired
	private AuthPort authPort;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private UserRepository userRepository;

	/** 해시 계산은 실제 빈으로 — 테스트가 별도 해시 구현을 가지면 계약이 두 벌이 된다. */
	@Autowired
	private RefreshTokenWriter refreshTokenWriter;

	@Autowired
	private JwtSupport jwtSupport;

	@BeforeEach
	void clean() {
		CLOCK.setTo(BASE);
		refreshTokenRepository.deleteAll();
		userRepository.deleteAll();
	}

	private LoginResult login(String label) {
		return authPort.login(AnonymousKeyFixture.unique(label));
	}

	private RefreshToken storedRowOf(String rawToken) {
		return refreshTokenRepository.findByTokenHash(refreshTokenWriter.hash(rawToken)).orElseThrow();
	}

	private static void assertAuth005(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.as("auth.md §5-5 — refresh 실패는 전부 401 AUTH_005 하나다(프론트 행동이 같다)")
			.isEqualTo(ErrorCode.AUTH_005);
	}

	/**
	 * U9 · §5-5 — <b>회전</b>: refresh 제출 시 구 토큰을 폐기하고 새 쌍을 발급한다.
	 * 새 access 의 주체는 같은 사용자여야 하고, 구 refresh 행은 그 순간 폐기 표시가 남아야 한다.
	 */
	@Test
	@DisplayName("refresh 는 새 쌍을 발급하고 요청에 쓴 refresh 를 그 순간 폐기한다")
	void 회전() {
		LoginResult login = login("rotate");

		TokenPair rotated = authPort.refresh(login.refreshToken());

		assertThat(rotated.accessToken()).isNotBlank();
		assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(login.refreshToken());
		// 새 access 도 같은 사용자의 것이다 — 다른 사용자면 회전이 신원을 바꿔치기한 것
		assertThat(jwtSupport.parse(rotated.accessToken())).isEqualTo(login.userId());

		// 구 토큰은 폐기(revoked_at = 회전 시각 — §14-3 "SET revoked_at=?"), 새 토큰은 활성
		RefreshToken oldRow = storedRowOf(login.refreshToken());
		RefreshToken newRow = storedRowOf(rotated.refreshToken());
		assertThat(oldRow.isRevoked()).isTrue();
		assertThat(oldRow.getRevokedAt())
			.as("폐기 시각은 회전 시각이다 — 죽은 행이 재사용 감지(U9)의 증거로 남는다")
			.isEqualTo(BASE);
		assertThat(newRow.isRevoked()).isFalse();
		// 회전은 구 행의 갱신이 아니라 새 행 삽입이다 — 제자리 갱신이면 폐기 이력(U9 근거)이 사라진다
		assertThat(newRow.getId()).isNotEqualTo(oldRow.getId());
	}

	/** U9 — 회전으로 폐기된 구 refresh 는 더 이상 자격 증명이 아니다(구 토큰 즉시 무효). */
	@Test
	@DisplayName("회전된 구 refresh 의 재제출은 AUTH_005 다")
	void 회전_후_구_토큰은_무효다() {
		LoginResult login = login("stale-after-rotate");
		authPort.refresh(login.refreshToken());

		assertAuth005(() -> authPort.refresh(login.refreshToken()));
	}

	/**
	 * U9 · §5-5 — <b>재사용 감지 = 전체 폐기</b>: 폐기된 refresh 의 재제출은 탈취 신호다.
	 * 그 사용자의 refresh <b>전부</b>(다기기 것 + 방금 회전으로 받은 새것 포함)가 죽어야 한다.
	 * 정당한 사용자도 로그아웃되지만 재로그인 비용이 0 이라 손해가 없다 — 이 비대칭이 정책 근거다.
	 */
	@Test
	@DisplayName("폐기된 refresh 재사용은 그 사용자의 refresh 전부를 폐기한다 — 다기기·새 쌍 포함")
	void 재사용_감지는_전체_폐기다() {
		String key = AnonymousKeyFixture.unique("reuse");
		LoginResult deviceA = authPort.login(key);
		LoginResult deviceB = authPort.login(key);   // 같은 사용자의 두 번째 기기(§5-2 재호출 = 재로그인)
		TokenPair rotated = authPort.refresh(deviceA.refreshToken());

		// 이미 회전된 deviceA 토큰의 재사용 — 탈취 신호
		assertAuth005(() -> authPort.refresh(deviceA.refreshToken()));

		// 전체 폐기: 그 사용자의 행 전부 revoked
		assertThat(refreshTokenRepository.findAll())
			.filteredOn(token -> token.getUserId().equals(deviceA.userId()))
			.hasSize(3)
			.allSatisfy(token -> assertThat(token.isRevoked())
				.as("U9 — 재사용 감지 후 살아남은 refresh 가 있으면 탈취자가 그걸 쓴다")
				.isTrue());

		// 다른 기기(deviceB)와 회전으로 받은 새 쌍도 이후 전부 거부된다
		assertAuth005(() -> authPort.refresh(deviceB.refreshToken()));
		assertAuth005(() -> authPort.refresh(rotated.refreshToken()));
	}

	/**
	 * U9 전체 폐기의 <b>범위</b> — 다른 사용자의 refresh 는 건드리지 않는다.
	 * userId 축 UPDATE 가 WHERE 를 잃으면 전 사용자가 로그아웃된다.
	 */
	@Test
	@DisplayName("전체 폐기는 그 사용자만이다 — 남의 refresh 는 살아 있다")
	void 전체_폐기는_사용자_단위다() {
		LoginResult victim = login("victim");
		LoginResult bystander = login("bystander");
		authPort.refresh(victim.refreshToken());
		assertAuth005(() -> authPort.refresh(victim.refreshToken()));   // victim 전체 폐기 발동

		// bystander 는 영향이 없어야 한다 — 정상 회전이 계속된다
		TokenPair rotated = authPort.refresh(bystander.refreshToken());
		assertThat(rotated.refreshToken()).isNotBlank();
	}

	/**
	 * §14-3 — <b>미존재</b>: 발급된 적 없는 값은 AUTH_005 다. 미존재·만료·재사용을 코드로 가르지
	 * 않는 이유는 프론트 행동이 전부 "재로그인"으로 같기 때문이다(§5-5).
	 */
	@Test
	@DisplayName("발급된 적 없는 refresh 는 AUTH_005 다")
	void 미존재() {
		assertAuth005(() -> authPort.refresh("never-issued-token"));
	}

	/** U7(수명 14일) — 만료 직전(경계 -1초)은 아직 유효하다. 경계 테스트의 대조군. */
	@Test
	@DisplayName("만료 직전(14일 - 1초)의 refresh 는 회전된다")
	void 만료_직전은_유효하다() {
		LoginResult login = login("almost-expired");

		CLOCK.setTo(BASE.plusDays(14).minusSeconds(1));

		assertThat(authPort.refresh(login.refreshToken()).refreshToken()).isNotBlank();
	}

	/**
	 * U7·§14-3 — <b>만료</b>: 경계는 {@code expiresAt <= now} = 만료다(round-1-dev.md 판단 8).
	 * 발급 +14일 정각부터 무효. 만료는 회전이 아니므로 행은 폐기 표시 없이 남는다
	 * (재사용 감지의 근거 행을 지우지 않는 것과 같은 규율).
	 */
	@Test
	@DisplayName("발급 +14일 정각부터 refresh 는 만료다 — expiresAt <= now")
	void 만료_경계() {
		LoginResult login = login("expired");

		CLOCK.setTo(BASE.plusDays(14));

		assertAuth005(() -> authPort.refresh(login.refreshToken()));
		// 만료 거부는 회전·폐기가 아니다 — 행 상태는 그대로다
		assertThat(storedRowOf(login.refreshToken()).isRevoked()).isFalse();
	}

	/**
	 * U10 · §5-5 — <b>원문을 저장하지 않는다.</b> DB 에 남는 것은 SHA-256 hex 64자뿐이고,
	 * 원문으로는 조회조차 되지 않아야 한다(U6 와 같은 규율 — 행이 새면 토큰이 새는 구조를 안 만든다).
	 * 로그인 발급분·회전 발급분 모두 같다.
	 */
	@Test
	@DisplayName("DB 에는 refresh 원문이 없다 — SHA-256 hex 해시만 저장된다")
	void U10_해시_저장_원문_부재() {
		LoginResult login = login("hash-only");
		TokenPair rotated = authPort.refresh(login.refreshToken());

		for (String raw : new String[] {login.refreshToken(), rotated.refreshToken()}) {
			RefreshToken stored = storedRowOf(raw);
			assertThat(stored.getTokenHash())
				.isEqualTo(refreshTokenWriter.hash(raw))
				.isNotEqualTo(raw)
				.hasSize(64)
				.matches("[0-9a-f]{64}");
			assertThat(refreshTokenRepository.findByTokenHash(raw))
				.as("원문으로 찾히면 원문이 저장돼 있다는 뜻이다(U10 위반)")
				.isEmpty();
		}

		// 테이블 전체를 훑어도 원문은 어느 컬럼 값에도 없다
		assertThat(refreshTokenRepository.findAll())
			.allSatisfy(row -> assertThat(row.getTokenHash())
				.doesNotContain(login.refreshToken())
				.doesNotContain(rotated.refreshToken()));
	}

	/** U7 — 만료는 발급 시각 +14일이다. 행의 expiresAt 이 정책 수치와 다르면 여기서 먼저 빨개진다. */
	@Test
	@DisplayName("refresh 만료 시각은 발급 +14일이다")
	void 만료_시각은_14일이다() {
		LoginResult login = login("ttl");

		assertThat(storedRowOf(login.refreshToken()).getExpiresAt())
			.as("auth.md U7 확정 — refresh 수명 14일")
			.isEqualTo(BASE.plusDays(14));
	}
}
