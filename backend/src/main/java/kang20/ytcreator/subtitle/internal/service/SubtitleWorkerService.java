package kang20.ytcreator.subtitle.internal.service;

import java.util.function.Supplier;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleWorkerService implements SubtitleWorkerPort {

	private final JobWriter jobWriter;

	@Override
	public JobStatus attachScript(JobId jobId, StorageKey script) {
		return settleOnRace(() -> jobWriter.attachScript(jobId, script)).status();
	}

	@Override
	public JobStatus attachSubtitle(JobId jobId, StorageKey subtitle) {
		return settleOnRace(() -> jobWriter.attachSubtitle(jobId, subtitle)).status();
	}

	/** 동시에 도착한 완료 통지의 진 쪽은 승자 상태를 다시 읽어 "무시하고 현재 상태"로 수렴한다. */
	private TransitionResult settleOnRace(Supplier<TransitionResult> attempt) {
		try {
			return attempt.get();
		} catch (OptimisticLockingFailureException lost) {
			return attempt.get();
		}
	}
}
