package kang20.ytcreator.subtitle.internal.entity.dto;

import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;

/**
 * 조회 응답 — 저장소 키가 아니라 수명이 짧은 링크만 내려간다.
 * {@code scriptUrl} 은 사용자 대기 구간(COMPLETED_SCRIPT)에서만, {@code subtitleUrl} 은 완료에서만 채워진다.
 */
public record JobDetail(
	long jobId,
	JobStatus status,
	FailureCause failureCause,
	boolean expired,
	String scriptUrl,
	String subtitleUrl,
	SubtitleFileFormat format
) {
}
