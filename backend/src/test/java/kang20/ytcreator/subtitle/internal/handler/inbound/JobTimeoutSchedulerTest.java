package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;

import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-84 · REQ-133 의 구동 어댑터 — 주기 자체는 재량이라 검증 대상이 아니고,
 * 두 배치가 각각 맞는 포트 메서드로 위임되는지만 고정한다(마감과 재개가 뒤바뀌면
 * 사용자 대기 작업이 재개되고 멈춘 작업이 방치 마감된다).
 */
class JobTimeoutSchedulerTest {

	@Test
	@DisplayName("마감 주기는 closeTimedOut 으로만 위임한다")
	void 마감_주기는_closeTimedOut_으로만_위임한다() {
		SubtitleTimeoutPort port = mock(SubtitleTimeoutPort.class);

		new JobTimeoutScheduler(port).run();

		verify(port, only()).closeTimedOut();
	}

	@Test
	@DisplayName("재개 주기는 redispatchStalled 로만 위임한다")
	void 재개_주기는_redispatchStalled_로만_위임한다() {
		SubtitleTimeoutPort port = mock(SubtitleTimeoutPort.class);

		new JobTimeoutScheduler(port).redispatch();

		verify(port, only()).redispatchStalled();
	}
}
