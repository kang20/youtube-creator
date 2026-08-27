package kang20.ytcreator.subtitle.internal.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleTimeoutService implements SubtitleTimeoutPort {

	private static final Logger log = LoggerFactory.getLogger(SubtitleTimeoutService.class);

	private final JobRepository jobRepository;
	private final JobWriter jobWriter;
	private final WorkDispatcher workDispatcher;
	private final Clock clock;

	@Override
	public void closeTimedOut() {
		LocalDateTime bound = LocalDateTime.now(clock).minus(Job.JOB_TIMEOUT);
		List<Job> candidates = jobRepository.findByStatusNotInAndLastTransitionedAtBefore(
			List.of(JobStatus.COMPLETED_SUBTITLE, JobStatus.FAILURE), bound);
		for (Job candidate : candidates) {
			close(candidate.getId());
		}
	}

	@Override
	public void redispatchStalled() {
		LocalDateTime bound = LocalDateTime.now(clock).minus(Job.STALL_THRESHOLD);
		List<Job> candidates = jobRepository.findByStatusInAndLastTransitionedAtBefore(
			List.of(JobStatus.REQUEST_SCRIPT, JobStatus.REQUEST_SUBTITLE), bound);
		for (Job candidate : candidates) {
			redispatch(candidate.getId());
		}
	}

	private void close(JobId jobId) {
		try {
			jobWriter.closeIfTimedOut(jobId);
		} catch (RuntimeException e) {
			// 마감이 실패하면 작업이 열린 채 남아 다음 배치가 다시 시도한다 — 보상 유실을 조용히 넘기지 않는다
			log.error("[subtitle] 타임아웃 마감 실패 — jobId={}", jobId, e);
		}
	}

	private void redispatch(JobId jobId) {
		TransitionResult result;
		try {
			result = jobWriter.redispatchIfStalled(jobId);
		} catch (OptimisticLockingFailureException raced) {
			return;   // 그 사이 상태가 나아갔다 — 다음 주기가 다시 판정한다
		} catch (RuntimeException e) {
			log.error("[subtitle] 멈춘 작업 재개 실패 — jobId={}", jobId, e);
			return;
		}
		if (result.advanced() && result.status() != JobStatus.FAILURE) {
			dispatch(jobId, result.status());   // 멈춘 그 단계를 다시 시킨다 — 다음 단계로 넘기지 않는다
		}
	}

	private void dispatch(JobId jobId, JobStatus stage) {
		try {
			workDispatcher.dispatch(jobId, stage);
		} catch (RuntimeException e) {
			log.warn("[subtitle] 재개 의뢰 실패 — 다음 주기가 다시 시도한다. jobId={}, stage={}", jobId, stage, e);
		}
	}
}
