package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JobTimeoutSchedulerTest {

	private SubtitleTimeoutPort timeoutPort;
	private SubtitleDispatchPort dispatchPort;
	private JobTimeoutScheduler scheduler;

	@BeforeEach
	void 준비() {
		timeoutPort = mock(SubtitleTimeoutPort.class);
		dispatchPort = mock(SubtitleDispatchPort.class);
		scheduler = new JobTimeoutScheduler(timeoutPort, dispatchPort);
	}

	@Test
	@DisplayName("마감 주기는 closeTimedOut 으로만 위임한다")
	void 마감_주기는_closeTimedOut_으로만_위임한다() {
		scheduler.run();

		verify(timeoutPort, only()).closeTimedOut();
		verifyNoInteractions(dispatchPort);
	}

	@Test
	@DisplayName("재개 주기는 redispatchStalled 로만 위임한다")
	void 재개_주기는_redispatchStalled_로만_위임한다() {
		scheduler.redispatch();

		verify(timeoutPort, only()).redispatchStalled();
		verifyNoInteractions(dispatchPort);
	}

	@Test
	@DisplayName("재발행 주기는 republishUndelivered 로만 위임한다")
	void 재발행_주기는_republishUndelivered_로만_위임한다() {
		scheduler.republish();

		verify(dispatchPort, only()).republishUndelivered();
		verifyNoInteractions(timeoutPort);
	}
}
