package kang20.ytcreator.subtitle.internal.handler.inbound;

import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import lombok.RequiredArgsConstructor;

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
