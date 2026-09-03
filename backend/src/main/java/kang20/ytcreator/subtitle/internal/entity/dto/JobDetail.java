package kang20.ytcreator.subtitle.internal.entity.dto;

import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;

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
