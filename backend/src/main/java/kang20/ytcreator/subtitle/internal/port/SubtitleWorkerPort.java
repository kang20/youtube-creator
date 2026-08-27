package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;

/**
 * 워커 완료 통지의 진입 — 같은 단계의 통지가 두 번 와도 상태는 한 번만 나아간다.
 * 중복은 장애가 아니라 정상 경로다(재시도·재전송·재개가 전부 중복을 만든다).
 *
 * <p>⚠️ 수신 어댑터(큐 리스너·워커 API)는 워커 인프라와 함께 온다 — 지금은 포트까지다.
 */
public interface SubtitleWorkerPort {

	/** 워커가 만든 대본 초안을 달고 사용자 확정 대기로 넘긴다 — 0행 대본도 성공이다. */
	JobStatus attachScript(JobId jobId, StorageKey script);

	/** 산출된 자막 파일을 달고 완료로 넘긴다 — 완료 시 소모가 확정(commit)된다. */
	JobStatus attachSubtitle(JobId jobId, StorageKey subtitle);
}
