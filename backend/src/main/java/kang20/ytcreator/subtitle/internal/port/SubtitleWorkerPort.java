package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

public interface SubtitleWorkerPort {

	JobStatus attachScript(JobId jobId);

	JobStatus attachSubtitle(JobId jobId);
}
