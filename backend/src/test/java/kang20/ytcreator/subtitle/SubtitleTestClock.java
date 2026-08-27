package kang20.ytcreator.subtitle;

import java.time.LocalDateTime;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import kang20.ytcreator.base.MutableClock;

/**
 * subtitle 모듈 테스트 공용 시계 — {@code TimeConfig} 를 직접 import 하면 Modulith 빈 선별기가
 * 죽는다(AuthServiceTest javadoc 실측). 타임아웃(24h)·재개 임계(30분) 경계를 한 컨텍스트에서
 * 재현해야 하므로 {@code Clock.fixed} 가 아니라 {@link MutableClock} 를 꽂는다.
 *
 * <p>⚠️ 컨텍스트가 클래스 간에 공유되므로 각 테스트는 {@code @BeforeEach} 에서
 * {@code clock.setTo(BASE)} 로 되돌려야 한다.
 */
@TestConfiguration
public class SubtitleTestClock {

	public static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 19, 12, 0, 0);

	@Bean
	MutableClock clock() {
		return new MutableClock(BASE);
	}
}
