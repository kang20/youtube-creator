package kang20.ytcreator.subtitle.internal.entity.dto;

import kang20.ytcreator.subtitle.internal.entity.JobStatus;

public record TransitionResult(boolean advanced, JobStatus status) {
}
