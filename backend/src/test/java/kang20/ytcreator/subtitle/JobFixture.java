package kang20.ytcreator.subtitle;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.handler.outbound.repository.JobRepository;

/**
 * 작업 테스트 데이터 표준화(testing.md 작성 원칙 1).
 *
 * <p>작업은 {@code open → receiveSource → attachScript → confirmScript → attachSubtitle} 의
 * <b>공개 행위만으로</b> 목표 상태까지 전진시킨다 — 리플렉션으로 상태를 비틀면 전이 규칙이
 * 검증에서 빠진다. {@code receiveSource} 가 식별자로 원본 키를 채번하므로 저장이 선행돼야 한다.
 *
 * <p>{@code CONFIRM_SCRIPT} 는 만들 수 없다 — 저장되지 않는 통과 상태다(subtitle-v1
 * "머무는 상태가 아니다").
 */
final class JobFixture {

	static final UserId OWNER = new UserId(4242L);

	static final UserId OTHER = new UserId(9999L);

	static final StorageKey SCRIPT_KEY = new StorageKey("worker/out/script-draft.md");

	static final StorageKey SUBTITLE_KEY = new StorageKey("worker/out/subtitle.md");

	private JobFixture() {
	}

	static Job jobAt(JobStatus target, JobRepository repository, UserId owner, LocalDateTime now) {
		Job job = repository.save(Job.open(owner, now));
		advance(job, target, now);
		return repository.save(job);
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
		job.attachScript(SCRIPT_KEY, now);
		if (target == JobStatus.COMPLETED_SCRIPT) {
			return;
		}
		job.confirmScript(false, now);
		if (target == JobStatus.REQUEST_SUBTITLE) {
			return;
		}
		job.attachSubtitle(SUBTITLE_KEY, now);
		if (target == JobStatus.COMPLETED_SUBTITLE) {
			return;
		}
		throw new IllegalArgumentException("CONFIRM_SCRIPT 는 저장되지 않는 통과 상태라 만들 수 없다");
	}
}
