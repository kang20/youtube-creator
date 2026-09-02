package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

/**
 * 워커 완료 통지의 진입 — 같은 단계의 통지가 두 번 와도 상태는 한 번만 나아간다.
 * 통지는 힌트고 근거는 산출물이다: 서버가 정한 위치에 실물이 없으면 상태 불일치(SUBTITLE_002)로 거절한다.
 */
public interface SubtitleWorkerPort {

	JobStatus attachScript(JobId jobId);

	/** 완료로 넘기며 소모를 확정(commit)한다. */
	JobStatus attachSubtitle(JobId jobId);
}
