package kang20.ytcreator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * BaseTimeEntity 의 @CreatedDate·@LastModifiedDate 활성화.
 * 별도 클래스로 둔 이유: @DataJpaTest 등에서 필요할 때만 import 하기 위해.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
