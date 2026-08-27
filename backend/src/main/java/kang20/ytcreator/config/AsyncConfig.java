package kang20.ytcreator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} 처리 활성화 — {@code @ApplicationModuleListener} 가 {@code @Async} 를 포함한다.
 * 이게 없으면 {@code @Async} 가 조용히 무시돼 리스너가 발행 스레드에서 커밋 직후 동기 실행되고,
 * 지급 응답이 리스너 처리 시간을 그대로 떠안는다(30초 예산 침식).
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
