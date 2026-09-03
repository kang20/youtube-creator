package kang20.ytcreator.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import kang20.ytcreator.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DataJpaTest(excludeAutoConfiguration = DataJpaRepositoriesAutoConfiguration.class)
@Import(JpaAuditingConfig.class)
@EntityScan(basePackageClasses = BaseTimeEntityTest.class)
class BaseTimeEntityTest {

	@Entity(name = "auditing_probe")
	static class Probe extends BaseTimeEntity {
		@Id
		@GeneratedValue
		Long id;
	}

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	@Test
	@DisplayName("저장하면 생성·수정 시각이 자동으로 채워진다")
	void 감사_시각이_채워진다() {
		Probe probe = new Probe();

		entityManager.persist(probe);
		entityManager.flush();

		assertThat(probe.getCreatedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now());
		assertThat(probe.getUpdatedAt()).isNotNull();
	}
}
