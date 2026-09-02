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
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleTimeoutPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleTimeoutService implements SubtitleTimeoutPort {

	private static final Logger log = LoggerFactory.getLogger(SubtitleTimeoutService.class);

	private final JobRepository jobRepository;
	private final JobWriter jobWriter;
	private final StorageInspector storageInspector;
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
			recover(candidate.getId(), candidate.requestedStage());
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

	// 통지 유실과 연산 유실은 다른 사건이다 — 산출물이 있으면 워커를 다시 부르지 않고 그 산출물로 전진시킨다(조정)
	private void recover(JobId jobId, WorkStage stage) {
		try {
			if (storageInspector.exists(stage.output(jobId))) {
				reconcile(jobId, stage);
				return;
			}
			jobWriter.redispatchIfStalled(jobId);   // 다시 시킬 의뢰는 전이와 같은 트랜잭션의 아웃박스로 나간다
		} catch (OptimisticLockingFailureException raced) {
			return;   // 그 사이 상태가 나아갔다 — 다음 주기가 다시 판정한다
		} catch (RuntimeException e) {
			log.error("[subtitle] 멈춘 작업 회복 실패 — 다음 주기가 다시 본다. jobId={}, stage={}", jobId, stage, e);
		}
	}

	private void reconcile(JobId jobId, WorkStage stage) {
		TransitionResult result = switch (stage) {
			case SCRIPT -> jobWriter.attachScript(jobId);
			case SUBTITLE -> jobWriter.attachSubtitle(jobId);
		};
		if (result.advanced()) {
			log.info("[subtitle] 완료 통지 유실을 산출물로 회복 — jobId={}, stage={}", jobId, stage);
		}
	}
}
