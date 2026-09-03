package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;

public interface SubtitleDispatchPort {

	void dispatch(WorkRequested requested);

	void republishUndelivered();
}
