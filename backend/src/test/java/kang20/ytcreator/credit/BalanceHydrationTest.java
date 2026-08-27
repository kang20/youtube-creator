package kang20.ytcreator.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.credit.internal.entity.Balance;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * C6 — {@code Balance} 불변식이 <b>읽기 경로에서도</b> 도는지 본다.
 *
 * <p>🔴 이 테스트가 지키는 것은 값이 아니라 <b>매핑 방식</b>이다. {@code Balance} 가 record 라서
 * Hibernate 가 정규 생성자로 인스턴스화하고, 그래서 음수 검증이 하이드레이션에서도 돈다.
 * 누군가 record 를 일반 클래스로 바꾸면({@code @Instantiator} 없이) no-arg 생성자 + 필드 주입으로
 * 돌아가 검증이 <b>조용히</b> 죽는데, 그 순간 여기서만 드러난다.
 *
 * <p>DB 는 CHECK 제약을 걸지 않으므로(credit-v1.sql) 음수 행 자체는 만들어질 수 있다 — 수동
 * UPDATE·마이그레이션 사고가 그 경로다. 애플리케이션은 그런 행을 조용히 읽어들이면 안 된다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, BalanceHydrationTest.TestClockConfig.class})
class BalanceHydrationTest {

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.system(TimeConfig.KST);
		}
	}

	@Autowired
	private CreditBalanceRepository creditBalanceRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void 잔량을_비운다() {
		creditBalanceRepository.deleteAll();
	}

	/** C6 — 정상 값은 그대로 왕복한다 — 임베더블 매핑이 컬럼 하나에 붙어 있다는 확인 */
	@Test
	@Transactional
	@DisplayName("잔량은 balance 컬럼 하나로 그대로 왕복한다")
	void 왕복() {
		insertBalanceRow(new UserId(9201L), 5L);
		entityManager.clear();

		assertThat(creditBalanceRepository.findAll())
			.singleElement()
			.extracting(row -> row.getBalance())
			.isEqualTo(new Balance(5L));
	}

	/** C6 — 음수 행을 읽으면 터진다. 조용히 통과하면 매핑이 생성자를 안 타는 것이다 */
	@Test
	@Transactional
	@DisplayName("음수 잔량 행은 읽는 순간 터진다 — 하이드레이션도 생성자를 탄다")
	void 음수_행은_읽히지_않는다() {
		insertBalanceRow(new UserId(9202L), -1L);
		entityManager.clear();

		assertThatThrownBy(() -> creditBalanceRepository.findAll())
			.as("조용히 Balance(-1) 이 만들어지면 VO 의 불변식이 읽기 경로에서 무의미해진다")
			.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	private void insertBalanceRow(UserId userId, long balance) {
		entityManager.createNativeQuery(
				"insert into credit_balance (user_id, balance, created_at, updated_at) "
					+ "values (:userId, :balance, :now, :now)")
			.setParameter("userId", userId.longValue())
			.setParameter("balance", balance)
			.setParameter("now", LocalDateTime.now())
			.executeUpdate();
	}
}
