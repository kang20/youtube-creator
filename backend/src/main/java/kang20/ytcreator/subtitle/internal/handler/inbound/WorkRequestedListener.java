package kang20.ytcreator.subtitle.internal.handler.inbound;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import lombok.RequiredArgsConstructor;

/**
 * 아웃박스 리스너 — 커밋 뒤 비동기로 돈다. 여기서 던진 예외가 발행을 미완료로 남겨 재발행 대상이 되게 한다.
 * 삼키면 큐에 닿지 못한 의뢰가 완료로 표시된다.
 */
@Component
@RequiredArgsConstructor
public class WorkRequestedListener {
	private final SubtitleDispatchPort subtitleDispatchPort;

	@ApplicationModuleListener
	public void on(WorkRequested requested) {
		subtitleDispatchPort.dispatch(requested);
	}
}
