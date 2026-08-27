package kang20.ytcreator.subtitle.internal.handler.inbound;

import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import lombok.RequiredArgsConstructor;

/**
 * 방치 마감 배치의 구동 어댑터 — 주기는 우리 재량이다(상한 24h 대비 1시간이면 지연이 무해하다).
 */
@Component
@RequiredArgsConstructor
public class JobTimeoutScheduler {
	private final SubtitleTimeoutPort subtitleTimeoutPort;

	@Scheduled(initialDelay = 1, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
	public void run() {
		subtitleTimeoutPort.closeTimedOut();
	}

	// 재개 임계 30분 대비 10분 주기 — 감지 지연이 최대 한 주기다
	@Scheduled(initialDelay = 5, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
	public void redispatch() {
		subtitleTimeoutPort.redispatchStalled();
	}
}
