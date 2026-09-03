package kang20.ytcreator.subtitle.internal.entity.dto;

import java.time.LocalDateTime;
import java.util.List;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

public record JobList(List<Item> jobs) {

	public record Item(long jobId, JobStatus status, boolean expired, LocalDateTime createdAt) {
	}
}
