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

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, RefreshRotationTest.TestClockConfig.class})
class RefreshRotationTest {

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

	@Test
	@DisplayName("refresh 는 새 쌍을 발급하고 요청에 쓴 refresh 를 그 순간 폐기한다")
	void 회전() {
		LoginResult login = login("rotate");

		TokenPair rotated = authPort.refresh(login.refreshToken());

		assertThat(rotated.accessToken()).isNotBlank();
		assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(login.refreshToken());
		// 새 access 도 같은 사용자의 것이다 — 다른 사용자면 회전이 신원을 바꿔치기한 것
		assertThat(jwtSupport.parse(rotated.accessToken()).userId()).isEqualTo(login.userId());

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

	@Test
	@DisplayName("회전된 구 refresh 의 재제출은 AUTH_005 다")
	void 회전_후_구_토큰은_무효다() {
		LoginResult login = login("stale-after-rotate");
		authPort.refresh(login.refreshToken());

		assertAuth005(() -> authPort.refresh(login.refreshToken()));
	}

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

	@Test
	@DisplayName("발급된 적 없는 refresh 는 AUTH_005 다")
	void 미존재() {
		assertAuth005(() -> authPort.refresh("never-issued-token"));
	}

	@Test
	@DisplayName("만료 직전(14일 - 1초)의 refresh 는 회전된다")
	void 만료_직전은_유효하다() {
		LoginResult login = login("almost-expired");

		CLOCK.setTo(BASE.plusDays(14).minusSeconds(1));

		assertThat(authPort.refresh(login.refreshToken()).refreshToken()).isNotBlank();
	}

	@Test
	@DisplayName("발급 +14일 정각부터 refresh 는 만료다 — expiresAt <= now")
	void 만료_경계() {
		LoginResult login = login("expired");

		CLOCK.setTo(BASE.plusDays(14));

		assertAuth005(() -> authPort.refresh(login.refreshToken()));
		// 만료 거부는 회전·폐기가 아니다 — 행 상태는 그대로다
		assertThat(storedRowOf(login.refreshToken()).isRevoked()).isFalse();
	}

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

	@Test
	@DisplayName("refresh 만료 시각은 발급 +14일이다")
	void 만료_시각은_14일이다() {
		LoginResult login = login("ttl");

		assertThat(storedRowOf(login.refreshToken()).getExpiresAt())
			.as("auth.md U7 확정 — refresh 수명 14일")
			.isEqualTo(BASE.plusDays(14));
	}
}
