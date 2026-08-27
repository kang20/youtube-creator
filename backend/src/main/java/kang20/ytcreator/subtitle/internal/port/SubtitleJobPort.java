package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.dto.JobDetail;
import kang20.ytcreator.subtitle.internal.entity.dto.JobList;
import kang20.ytcreator.subtitle.internal.entity.dto.JobOpened;

/**
 * 사용자 흐름의 유일한 진입 — 이용 게이트는 {@link #open} 에만 걸리고 조회·수령에는 걸리지 않는다.
 * 없는 작업과 남의 작업은 같은 답(SUBTITLE_001)이다 — 존재 자체를 알려주지 않는다.
 */
public interface SubtitleJobPort {

	/**
	 * 작업을 연다 — 자격 판정과 소모는 {@code PaymentUsagePort.consume} 하나로, 작업 열기와 한 트랜잭션이다.
	 * 소모가 거부되면 작업도 태어나지 않는다. 성공하면 원본 업로드용 단명 링크를 돌려준다.
	 */
	JobOpened open(UserId userId);

	/** 원본 수신을 확인하고 대본 생성을 의뢰한다. 재요청은 오류가 아니라 현재 상태를 돌려준다. */
	JobStatus receiveSource(JobId jobId, UserId userId);

	/** 대본을 확정하고 자막 산출을 의뢰한다 — 멱등. 두 번째 확정은 현재 상태를 그대로 돌려준다. */
	JobStatus confirmScript(JobId jobId, UserId userId);

	/** 현재 상태를 답한다 — 폴링의 대상. 언제 물어도 처리 완료를 기다리지 않고 즉시 답한다. */
	JobDetail detail(JobId jobId, UserId userId);

	/** 최근 작업과 상태 — 아카이브가 아니라 이탈했다 돌아온 사용자의 복구 장치다. */
	JobList list(UserId userId);
}
