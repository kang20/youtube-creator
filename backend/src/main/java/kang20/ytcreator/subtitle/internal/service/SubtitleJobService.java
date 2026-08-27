package kang20.ytcreator.subtitle.internal.service;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import kang20.ytcreator.subtitle.internal.port.SubtitleJobPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import kang20.ytcreator.subtitle.internal.service.support.SignedUrlIssuer;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import kang20.ytcreator.subtitle.internal.service.support.WorkDispatcher;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleJobService implements SubtitleJobPort {

	private static final Logger log = LoggerFactory.getLogger(SubtitleJobService.class);

	private final JobWriter jobWriter;
	private final JobRepository jobRepository;
	private final WorkDispatcher workDispatcher;
	private final SignedUrlIssuer signedUrlIssuer;
	private final StorageInspector storageInspector;

	@Override
	public JobOpened open(UserId userId) {
		Job job = jobWriter.openWithValidatePayment(userId);

		String uploadUrl = signedUrlIssuer.issue(StorageKey.sourceOf(job.getId()), true);

		return new JobOpened(job.getId().longValue(), uploadUrl);
	}

	@Override
	public JobStatus receiveSource(JobId jobId, UserId userId) {
		owned(jobId, userId);   // 소유 판정이 먼저다 — 없는 작업이 저장소 확인 결과로 답하면 코드가 갈린다
		// 전이 전에 원본 실물을 확인한다 — 클라이언트의 "다 올렸다"만으로 착수하지 않는다
		if (!storageInspector.exists(StorageKey.sourceOf(jobId))) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);
		}
		TransitionResult result = settleOnRace(() -> jobWriter.receiveSource(jobId, userId));
		if (result.advanced()) {
			dispatch(jobId, result.status());
		}
		return result.status();
	}

	@Override
	public JobStatus confirmScript(JobId jobId, UserId userId) {
		Job job = owned(jobId, userId);
		// 확정 대기가 아니면 판정할 것이 없다 — 재요청·거부 경로는 저장소를 부르지 않는다
		boolean scriptEmpty = job.getStatus() == JobStatus.COMPLETED_SCRIPT
			&& storageInspector.scriptEmpty(job.getScript());
		TransitionResult result = settleOnRace(() -> jobWriter.confirmScript(jobId, userId, scriptEmpty));
		if (result.advanced() && result.status() == JobStatus.REQUEST_SUBTITLE) {
			dispatch(jobId, result.status());
		}
		return result.status();
	}

	@Override
	public JobDetail detail(JobId jobId, UserId userId) {
		Job job = owned(jobId, userId);

		String scriptUrl = job.getStatus() == JobStatus.COMPLETED_SCRIPT
			? signedUrlIssuer.issue(job.getScript(), true)   // 확정 전까지만 수정 권한을 연다
			: null;
		String subtitleUrl = job.getStatus() == JobStatus.COMPLETED_SUBTITLE && job.getSubtitle() != null
			? signedUrlIssuer.issue(job.getSubtitle(), false)
			: null;
		return new JobDetail(job.getId().longValue(), job.getStatus(), job.getFailureCause(), job.expired(),
			scriptUrl, subtitleUrl, subtitleUrl != null ? SubtitleFileFormat.MARKDOWN : null);
	}

	@Override
	public JobList list(UserId userId) {
		List<JobList.Item> jobs = jobRepository.findByUserIdOrderByIdDesc(userId).stream()
			.map(job -> new JobList.Item(
				job.getId().longValue(), job.getStatus(), job.expired(), job.getCreatedAt()))
			.toList();
		return new JobList(jobs);
	}

	private Job owned(JobId jobId, UserId userId) {
		return jobRepository.findById(jobId)
			.filter(found -> found.ownedBy(userId))
			.orElseThrow(() -> new BusinessException(ErrorCode.SUBTITLE_001));
	}

	/** 낙관적 잠금에 진 쪽은 승자가 만든 상태를 다시 읽어 멱등 경로로 수렴한다 — 오류가 아니다. */
	private TransitionResult settleOnRace(Supplier<TransitionResult> attempt) {
		try {
			return attempt.get();
		} catch (OptimisticLockingFailureException lost) {
			return attempt.get();
		}
	}

	private void dispatch(JobId jobId, JobStatus stage) {
		try {
			workDispatcher.dispatch(jobId, stage);
		} catch (RuntimeException e) {
			// 진실의 기준은 작업의 상태다 — 의뢰 유실은 "상태가 나아가지 않음"으로 드러나 회복이 다시 넘긴다
			log.warn("[subtitle] 작업 의뢰 실패 — jobId={}, stage={}", jobId, stage, e);
		}
	}
}
