package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;

/**
 * 처리 의뢰 — 워커를 직접 부르지 않고 넘겨 두면 워커가 자기 여유에 맞춰 가져간다.
 * 부르는 것은 아웃박스 리스너뿐이고, 실패는 예외로 올린다 — 삼키면 아웃박스가 완료로 표시돼 재발행되지 않는다.
 */
@Support
public interface WorkDispatcher {

	void dispatch(JobId jobId, WorkStage stage);
}
