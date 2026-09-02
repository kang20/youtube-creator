package kang20.ytcreator.subtitle.internal.service.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;

/** 큐 설정이 꺼진 조립 — 조용히 삼키면 의뢰가 가짜로 성립하므로 거부한다(subtitle-v3 처리 의뢰). */
@Support
@ConditionalOnProperty(name = "ytcreator.subtitle.queue.enabled", havingValue = "false", matchIfMissing = true)
public class UnavailableWorkDispatcher implements WorkDispatcher {

	@Override
	public void dispatch(JobId jobId, WorkStage stage) {
		throw new UnsupportedOperationException("워커 큐가 꺼져 있다 — 작업을 넘길 곳이 없다");
	}
}
