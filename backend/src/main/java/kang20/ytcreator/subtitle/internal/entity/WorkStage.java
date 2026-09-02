package kang20.ytcreator.subtitle.internal.entity;

/** 워커와의 계약 — 상태 축(JobStatus)을 큐 페이로드로 흘리지 않는다(subtitle-v3 워커 의뢰 단계). */
public enum WorkStage {
	SCRIPT,
	SUBTITLE;

	public StorageKey input(JobId jobId) {
		return switch (this) {
			case SCRIPT -> StorageKey.sourceOf(jobId);
			case SUBTITLE -> StorageKey.scriptOf(jobId);
		};
	}

	/** 이 위치에 실물이 있으면 그 단계는 끝난 것이다 — 산출물의 존재가 멱등 키다. */
	public StorageKey output(JobId jobId) {
		return switch (this) {
			case SCRIPT -> StorageKey.scriptOf(jobId);
			case SUBTITLE -> StorageKey.subtitleOf(jobId);
		};
	}
}
