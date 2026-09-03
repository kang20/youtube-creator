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
