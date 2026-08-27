package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

/** 임시 어댑터 — 워커 큐 실물이 오면 대체된다. 부팅만 지킨다(subtitle-v1 이용권 연동과 같은 원리). */
@Support
public class UnavailableWorkDispatcher implements WorkDispatcher {

	@Override
	public void dispatch(JobId jobId, JobStatus stage) {
		throw new UnsupportedOperationException("워커 큐 구현 전 — 작업을 넘길 곳이 없다");
	}
}
