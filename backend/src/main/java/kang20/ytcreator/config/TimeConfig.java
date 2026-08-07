package kang20.ytcreator.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간은 반드시 주입받는다 — LocalDateTime.now() 직접 호출은 테스트를 100% 로 만들 수 없게 한다.
 * 테스트에서는 Clock.fixed 로 갈아끼운다.
 */
@Configuration
public class TimeConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Bean
	public Clock clock() {
		return Clock.system(KST);
	}
}
