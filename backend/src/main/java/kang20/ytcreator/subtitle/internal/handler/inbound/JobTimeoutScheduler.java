package kang20.ytcreator.subtitle.internal.handler.inbound;

import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import lombok.RequiredArgsConstructor;

/**
 * 배치 구동 어댑터 — 주기는 우리 재량이다. 마감은 상한 24h 대비 1시간, 재개는 임계 30분 대비 10분,
 * 재발행은 지연 1분 대비 1분이면 감지 지연이 최대 한 주기다.
 */
@Component
@RequiredArgsConstructor
public class JobTimeoutScheduler {
	private final SubtitleTimeoutPort subtitleTimeoutPort;
	private final SubtitleDispatchPort subtitleDispatchPort;

	@Scheduled(initialDelay = 1, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
	public void run() {
		subtitleTimeoutPort.closeTimedOut();
	}

	@Scheduled(initialDelay = 5, fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
	public void redispatch() {
		subtitleTimeoutPort.redispatchStalled();
	}

	@Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void republish() {
		subtitleDispatchPort.republishUndelivered();
	}
}
