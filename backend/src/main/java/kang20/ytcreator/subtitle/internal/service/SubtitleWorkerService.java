package kang20.ytcreator.subtitle.internal.service;

import java.util.function.Supplier;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.dto.TransitionResult;
import kang20.ytcreator.subtitle.internal.port.SubtitleWorkerPort;
import kang20.ytcreator.subtitle.internal.service.support.JobWriter;
import kang20.ytcreator.subtitle.internal.service.support.StorageInspector;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubtitleWorkerService implements SubtitleWorkerPort {

	private final JobWriter jobWriter;
	private final StorageInspector storageInspector;

	@Override
	public JobStatus attachScript(JobId jobId) {
		requireArtifact(StorageKey.scriptOf(jobId));
		return settleOnRace(() -> jobWriter.attachScript(jobId)).status();
	}

	@Override
	public JobStatus attachSubtitle(JobId jobId) {
		requireArtifact(StorageKey.subtitleOf(jobId));
		return settleOnRace(() -> jobWriter.attachSubtitle(jobId)).status();
	}

	// 통지는 힌트다 — 실물 없이 전이하면 사용자 대기 구간에서 깨진 편집 화면을 보다 24시간 뒤 방치로 닫힌다
	private void requireArtifact(StorageKey artifact) {
		if (!storageInspector.exists(artifact)) {
			throw new BusinessException(ErrorCode.SUBTITLE_002);
		}
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
