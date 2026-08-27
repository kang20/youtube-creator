package kang20.ytcreator.subtitle.internal.service.support;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

/**
 * 처리 의뢰 — 워커를 직접 부르지 않고 넘겨 두면 워커가 자기 여유에 맞춰 가져간다.
 *
 * <p>넘긴 것은 사라질 수 있다 — 진실의 기준은 넘겼다는 사실이 아니라 작업의 상태다.
 * 전달은 최소 At-Least-Once 여야 하고, 같은 작업을 다시 넘겨도 결과물은 한 벌이어야 한다.
 */
@Support
public interface WorkDispatcher {

	/** {@code stage} 는 시킬 단계다 — REQUEST_SCRIPT(대본 생성) 또는 REQUEST_SUBTITLE(자막 산출). */
	void dispatch(JobId jobId, JobStatus stage);
}
