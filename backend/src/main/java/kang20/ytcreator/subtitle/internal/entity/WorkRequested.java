package kang20.ytcreator.subtitle.internal.entity;

import java.time.Duration;

public record WorkRequested(long jobId, WorkStage stage) {

	public static final Duration REPUBLISH_DELAY = Duration.ofMinutes(1);

	public static WorkRequested of(JobId jobId, WorkStage stage) {
		return new WorkRequested(jobId.longValue(), stage);
	}

	public JobId job() {
		return new JobId(jobId);
	}
}
