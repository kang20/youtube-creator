package kang20.ytcreator.subtitle.internal.service.support;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.transaction.annotation.Transactional;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;
import lombok.RequiredArgsConstructor;

/**
 * 상태 전이의 트랜잭션 쓰기 빈 — 결제 호출은 전이와 <b>같은 트랜잭션</b>이다.
 * 결제 호출이 실패하면 전이도 되돌아가고, 재시도(사용자 재요청·워커 재전송·다음 배치)가 다시 부른다 —
 * 보상 호출 유실이 조용히 넘어가지 않게 하는 유일한 장치다.
 */
@Support
@RequiredArgsConstructor
public class JobWriter {
	private final JobRepository jobRepository;
	private final PaymentUsagePort paymentUsagePort;
	private final Clock clock;

	@Transactional
	public Job openWithValidatePayment(UserId userId) {
		Job job = jobRepository.save(Job.open(userId, now()));
		// 거부는 결제 계열의 오류로 그대로 전파한다 — 이 도메인의 코드가 아니다. 롤백으로 작업도 태어나지 않는다
		paymentUsagePort.consume(userId, ref(job));
		return job;
	}

	@Transactional
	public TransitionResult receiveSource(JobId jobId, UserId userId) {
		Job job = owned(jobId, userId);
		return new TransitionResult(job.receiveSource(now()), job.getStatus());
	}

	@Transactional
	public TransitionResult confirmScript(JobId jobId, UserId userId, boolean scriptEmpty) {
		Job job = owned(jobId, userId);
		boolean advanced = job.confirmScript(scriptEmpty, now());
		if (advanced && job.getStatus() == JobStatus.COMPLETED_SUBTITLE) {
			paymentUsagePort.commit(ref(job));   // 빈 대본 건너뜀도 소모가 확정되는 완료다
		}
		return new TransitionResult(advanced, job.getStatus());
	}

	@Transactional
	public TransitionResult attachScript(JobId jobId, StorageKey script) {
		Job job = find(jobId);
		return new TransitionResult(job.attachScript(script, now()), job.getStatus());
	}

	@Transactional
	public TransitionResult attachSubtitle(JobId jobId, StorageKey subtitle) {
		Job job = find(jobId);
		boolean advanced = job.attachSubtitle(subtitle, now());
		if (advanced) {
			paymentUsagePort.commit(ref(job));
		}
		return new TransitionResult(advanced, job.getStatus());
	}

	/** 후보 조회와 마감 사이에 상태가 나아갔을 수 있다 — 같은 트랜잭션에서 다시 판정한다. */
	@Transactional
	public TransitionResult closeIfTimedOut(JobId jobId) {
		Job job = find(jobId);
		LocalDateTime now = now();
		if (job.abandoned(now)) {
			job.fail(FailureCause.ABANDONED, now);
			paymentUsagePort.commit(ref(job));   // 방치도 소모가 확정되는 사건이다
			return new TransitionResult(true, job.getStatus());
		}
		if (job.stalled(now, Job.JOB_TIMEOUT)) {
			job.fail(FailureCause.SERVER_FAULT, now);
			paymentUsagePort.release(ref(job));
			return new TransitionResult(true, job.getStatus());
		}
		return new TransitionResult(false, job.getStatus());
	}

	/**
	 * 후보 조회와 재개 사이에 상태가 나아갔을 수 있다 — 같은 트랜잭션에서 다시 판정한다.
	 * 재개 한계(3회)를 다 쓴 작업은 SERVER_FAULT 로 닫고 이용권을 되돌린다(상태도 "회복 한계").
	 */
	@Transactional
	public TransitionResult redispatchIfStalled(JobId jobId) {
		Job job = find(jobId);
		LocalDateTime now = now();
		boolean redispatchable =
			(job.getStatus() == JobStatus.REQUEST_SCRIPT || job.getStatus() == JobStatus.REQUEST_SUBTITLE)
				&& job.stalled(now, Job.STALL_THRESHOLD);
		if (!redispatchable) {
			return new TransitionResult(false, job.getStatus());
		}
		if (job.redispatch(now)) {
			return new TransitionResult(true, job.getStatus());   // 상태는 그대로 — 서비스가 커밋 후 다시 넘긴다
		}
		job.fail(FailureCause.SERVER_FAULT, now);
		paymentUsagePort.release(ref(job));
		return new TransitionResult(true, job.getStatus());
	}

	private Job owned(JobId jobId, UserId userId) {
		Job job = find(jobId);
		if (!job.ownedBy(userId)) {
			throw new BusinessException(ErrorCode.SUBTITLE_001);   // 남의 작업 = 없는 작업과 같은 답
		}
		return job;
	}

	private Job find(JobId jobId) {
		return jobRepository.findById(jobId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SUBTITLE_001));
	}

	private String ref(Job job) {
		return String.valueOf(job.getId().longValue());
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
