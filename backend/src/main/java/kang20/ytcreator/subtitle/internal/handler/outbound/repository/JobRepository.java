package kang20.ytcreator.subtitle.internal.handler.outbound.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

public interface JobRepository extends JpaRepository<Job, JobId> {

	List<Job> findByUserIdOrderByIdDesc(UserId userId);

	List<Job> findByStatusNotInAndLastTransitionedAtBefore(Collection<JobStatus> statuses, LocalDateTime before);

	List<Job> findByStatusInAndLastTransitionedAtBefore(Collection<JobStatus> statuses, LocalDateTime before);
}
