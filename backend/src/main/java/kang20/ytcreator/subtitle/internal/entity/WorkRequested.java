package kang20.ytcreator.subtitle.internal.entity;

import java.time.Duration;

/**
 * 워커에게 시킬 일이 생겼다는 사건 — 등록은 {@link Job} 이, 발행은 저장이 한다.
 * 아웃박스(event_publication)에 JSON 으로 남으므로 타입 ID 가 아니라 원시 숫자를 싣는다(payment 이벤트 선례).
 */
public record WorkRequested(long jobId, WorkStage stage) {

	public static final Duration REPUBLISH_DELAY = Duration.ofMinutes(1);

	public static WorkRequested of(JobId jobId, WorkStage stage) {
		return new WorkRequested(jobId.longValue(), stage);
	}

	public JobId job() {
		return new JobId(jobId);
	}
}
