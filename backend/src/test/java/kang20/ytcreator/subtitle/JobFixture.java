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

/**
 * 작업 테스트 데이터 표준화(testing.md 작성 원칙 1).
 *
 * <p>작업은 {@code open → receiveSource → attachScript → confirmScript → attachSubtitle} 의
 * <b>공개 행위만으로</b> 목표 상태까지 전진시킨다 — 리플렉션으로 상태를 비틀면 전이 규칙이
 * 검증에서 빠진다. {@code receiveSource} 가 식별자로 원본 키를 채번하므로 저장이 선행돼야 한다.
 *
 * <p>⚠️ 마지막 저장이 전진 중 등록된 {@code WorkRequested}(SCRIPT·SUBTITLE)를 발행하고, 아웃박스 리스너가
 * <b>비동기로</b> 큐 대역에 넘긴다. 의뢰 횟수를 세는 테스트는 큐 대역을 함께 넘기는 오버로드를 쓴다 —
 * 픽스처의 의뢰가 다 닿기를 기다린 뒤 눈금을 0 에 맞춘다. 그냥 지우면 뒤늦게 도착한 의뢰가 검증을 깨뜨린다.
 */
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

	/** 목표 상태까지 전진하며 등록되는 워커 의뢰 수 — SCRIPT 하나, 확정을 지나면 SUBTITLE 하나 더. */
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
