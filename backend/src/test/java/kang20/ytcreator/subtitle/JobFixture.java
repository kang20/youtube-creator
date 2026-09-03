package kang20.ytcreator.subtitle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;

final class JobFixture {

	static final UserId OWNER = new UserId(4242L);

	static final UserId OTHER = new UserId(9999L);

	private static final long SETTLE_MILLIS = 5000;

	private JobFixture() {
	}

	static Job jobAt(JobStatus target, JobRepository repository, UserId owner, LocalDateTime now) {
		Job job = repository.save(Job.open(owner, now));
		advance(job, target, now);
		return repository.save(job);
	}

	static Job jobAt(JobStatus target, JobRepository repository, UserId owner, LocalDateTime now,
			WorkDispatcher dispatcher) {
		Job job = jobAt(target, repository, owner, now);
		int requests = workRequestsUntil(target);
		if (requests > 0) {
			verify(dispatcher, timeout(SETTLE_MILLIS).times(requests)).dispatch(eq(job.getId()), any());
		}
		clearInvocations(dispatcher);
		return job;
	}

	static int workRequestsUntil(JobStatus target) {
		return switch (target) {
			case CREATED, FAILURE -> 0;
			case REQUEST_SCRIPT, COMPLETED_SCRIPT -> 1;
			case REQUEST_SUBTITLE, COMPLETED_SUBTITLE -> 2;
		};
	}

	static void advance(Job job, JobStatus target, LocalDateTime now) {
		if (target == JobStatus.CREATED) {
			return;
		}
		if (target == JobStatus.FAILURE) {
			job.fail(FailureCause.SERVER_FAULT, now);
			return;
		}
		job.receiveSource(now);
		if (target == JobStatus.REQUEST_SCRIPT) {
			return;
		}
		job.attachScript(now);
		if (target == JobStatus.COMPLETED_SCRIPT) {
			return;
		}
		job.confirmScript(false, now);
		if (target == JobStatus.REQUEST_SUBTITLE) {
			return;
		}
		job.attachSubtitle(now);
	}
}
