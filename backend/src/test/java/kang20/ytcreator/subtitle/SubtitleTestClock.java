package kang20.ytcreator.subtitle;

import java.time.LocalDateTime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import kang20.ytcreator.base.MutableClock;

@TestConfiguration
public class SubtitleTestClock {

	public static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 19, 12, 0, 0);

	@Bean
	MutableClock clock() {
		return new MutableClock(BASE);
	}
}
