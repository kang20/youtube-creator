package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;

public interface SubtitleJobPort {

	JobOpened open(UserId userId);

	JobStatus receiveSource(JobId jobId, UserId userId);

	JobStatus confirmScript(JobId jobId, UserId userId);

	JobDetail detail(JobId jobId, UserId userId);

	JobList list(UserId userId);
}
