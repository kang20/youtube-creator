package kang20.ytcreator.subtitle.internal.handler.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * 아웃박스 리스너 — 의뢰를 포트로 넘기고, 실패를 삼키지 않는다(subtitle-v3 워커 의뢰 사건).
 * 아웃박스에 남는 근거는 {@code @ApplicationModuleListener}(트랜잭셔널 계열)라는 사실 자체다.
 */
class WorkRequestedListenerTest {

	private static final WorkRequested REQUESTED = new WorkRequested(7L, WorkStage.SCRIPT);

	@Test
	@DisplayName("의뢰 사건은 그대로 처리 의뢰 포트로 넘어간다")
	void 의뢰_사건은_그대로_처리_의뢰_포트로_넘어간다() {
		SubtitleDispatchPort port = mock(SubtitleDispatchPort.class);

		new WorkRequestedListener(port).on(REQUESTED);

		verify(port).dispatch(REQUESTED);
	}

	@Test
	@DisplayName("넘기기 실패는 삼키지 않는다 — 삼키면 아웃박스가 완료로 표시된다")
	void 넘기기_실패는_삼키지_않는다() {
		SubtitleDispatchPort port = mock(SubtitleDispatchPort.class);
		doThrow(new IllegalStateException("queue unavailable")).when(port).dispatch(any());

		assertThatThrownBy(() -> new WorkRequestedListener(port).on(REQUESTED))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("리스너는 트랜잭셔널 계열(@ApplicationModuleListener)이다 — 그래야 발행이 아웃박스에 남는다")
	void 리스너는_모듈_리스너다() throws NoSuchMethodException {
		assertThat(WorkRequestedListener.class.getMethod("on", WorkRequested.class)
			.isAnnotationPresent(ApplicationModuleListener.class)).isTrue();
	}
}
