package kang20.ytcreator.subtitle.internal.service.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;

@Support
@ConditionalOnProperty(name = "ytcreator.subtitle.queue.enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableWorkDispatcher implements WorkDispatcher {

	@Override
	public void dispatch(JobId jobId, WorkStage stage) {
		throw new UnsupportedOperationException("워커 큐가 꺼져 있다 — 작업을 넘길 곳이 없다");
	}
}
