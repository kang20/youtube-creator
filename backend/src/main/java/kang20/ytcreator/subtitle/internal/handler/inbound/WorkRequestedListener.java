package kang20.ytcreator.subtitle.internal.handler.inbound;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.port.SubtitleDispatchPort;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WorkRequestedListener {
	private final SubtitleDispatchPort subtitleDispatchPort;

	@ApplicationModuleListener
	public void on(WorkRequested requested) {
		subtitleDispatchPort.dispatch(requested);
	}
}
