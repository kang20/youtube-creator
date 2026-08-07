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

/**
 * Auditing 이 실제로 시각을 채우는지 확인한다. 어노테이션만 붙이고 @EnableJpaAuditing 을
 * 빠뜨리면 조용히 null 이 들어가 NOT NULL 위반으로 운영에서 터진다.
 *
 * <p>{@code @EntityScan} 으로 엔티티를 이 테스트 패키지로 좁히므로 <b>리포지토리 자동 설정도 함께
 * 꺼야 한다.</b> 그러지 않으면 다른 모듈의 리포지토리(auth 의 UserRepository)가 스캔되어
 * "Not a managed type" 으로 컨텍스트가 뜨지 않는다 — 이 테스트는 shared 의 Auditing 배선만 본다.
 */
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
