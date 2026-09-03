package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;

@Support
public interface WorkDispatcher {

	void dispatch(JobId jobId, WorkStage stage);
}
