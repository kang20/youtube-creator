package kang20.ytcreator.subtitle.internal.entity;

public enum WorkStage {
	SCRIPT,
	SUBTITLE;

	public StorageKey input(JobId jobId) {
		return switch (this) {
			case SCRIPT -> StorageKey.sourceOf(jobId);
			case SUBTITLE -> StorageKey.scriptOf(jobId);
		};
	}

	public StorageKey output(JobId jobId) {
		return switch (this) {
			case SCRIPT -> StorageKey.scriptOf(jobId);
			case SUBTITLE -> StorageKey.subtitleOf(jobId);
		};
	}
}
