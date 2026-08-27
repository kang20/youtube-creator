package kang20.ytcreator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 활성화 — 없으면 방치 마감 배치(subtitle)가 조용히 돌지 않아
 * 타임아웃 작업이 영원히 닫히지 않는다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
