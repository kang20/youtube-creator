package kang20.ytcreator.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Period;

import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subtitle.internal.entity.FailureCause;
import kang20.ytcreator.subtitle.internal.entity.Job;
import kang20.ytcreator.subtitle.internal.entity.JobId;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;
import kang20.ytcreator.subtitle.internal.entity.StorageKey;
import kang20.ytcreator.subtitle.internal.entity.SubtitleFileFormat;
import kang20.ytcreator.subtitle.internal.entity.WorkRequested;
import kang20.ytcreator.subtitle.internal.entity.WorkStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 워커·클라이언트·결제 계열과의 이름·값 계약 — 값 하나하나가 프론트 화면 분기와
 * 돈의 방향을 정하므로 enum·상수를 통째로 고정한다(subtitle-v3 용어 사전 · 각 규칙).
 */
class SubtitleContractTest {

	private static final JobId JOB = new JobId(7L);

	/** REQ-7 · REQ-9 · REQ-100 · REQ-145 · REQ-150 — 머무는 자리만 값이다. CONFIRM_SCRIPT 도 만료도 없다 */
	@Test
	@DisplayName("작업 진행 상태는 6값이고 이름이 계약이다 — 확정과 만료는 값이 아니다")
	void 작업_진행_상태는_6값이고_이름이_계약이다() {
		assertThat(JobStatus.values()).extracting(Enum::name).containsExactly(
			"CREATED", "REQUEST_SCRIPT", "COMPLETED_SCRIPT", "REQUEST_SUBTITLE", "COMPLETED_SUBTITLE", "FAILURE");
	}

	/** REQ-153 — 입력 반려(USER_FAULT)는 실패 사유가 아니다. 값이 늘면 잔량 대조가 무너진다 */
	@Test
	@DisplayName("실패 사유는 두 값뿐이다 — USER_FAULT 는 없다")
	void 실패_사유는_두_값뿐이다() {
		assertThat(FailureCause.values()).extracting(Enum::name)
			.containsExactly("SERVER_FAULT", "ABANDONED");
	}

	/** REQ-35 · REQ-157 — md 확정. 이름 없이 암묵으로 두면 세 쪽이 각자 다른 형식을 가정한다 */
	@Test
	@DisplayName("자막 파일 형식은 MARKDOWN 하나뿐이다")
	void 자막_파일_형식은_MARKDOWN_하나뿐이다() {
		assertThat(SubtitleFileFormat.values()).containsExactly(SubtitleFileFormat.MARKDOWN);
	}

	/** 워커 의뢰 단계 — 상태 여섯 중 워커가 할 일은 둘뿐이고, 단계가 입력·산출물 위치를 스스로 답한다(v3) */
	@Test
	@DisplayName("워커 의뢰 단계는 SCRIPT·SUBTITLE 둘이고 입력·산출물 위치를 작업 번호로 답한다")
	void 워커_의뢰_단계_계약() {
		assertThat(WorkStage.values()).extracting(Enum::name).containsExactly("SCRIPT", "SUBTITLE");

		assertThat(WorkStage.SCRIPT.input(JOB)).isEqualTo(StorageKey.sourceOf(JOB));
		assertThat(WorkStage.SCRIPT.output(JOB)).isEqualTo(StorageKey.scriptOf(JOB));
		assertThat(WorkStage.SUBTITLE.input(JOB)).isEqualTo(StorageKey.scriptOf(JOB));
		assertThat(WorkStage.SUBTITLE.output(JOB)).isEqualTo(StorageKey.subtitleOf(JOB));
	}

	/** 워커 의뢰 사건 — 아웃박스에 JSON 으로 남으므로 원시 숫자를 싣고, 타입 ID 로 되돌릴 수 있다(v3) */
	@Test
	@DisplayName("워커 의뢰 사건은 원시 작업 번호를 싣고 타입 ID 로 되돌린다")
	void 워커_의뢰_사건_계약() {
		WorkRequested requested = WorkRequested.of(JOB, WorkStage.SUBTITLE);

		assertThat(requested).isEqualTo(new WorkRequested(7L, WorkStage.SUBTITLE));
		assertThat(requested.job()).isEqualTo(JOB);
	}

	/** REQ-31 · REQ-142 · REQ-159 · REQ-160 · REQ-162 · REQ-164 · REQ-167 — 정책 상수. 시간 축 셋은 서로 다른 상수다 */
	@Test
	@DisplayName("정책 상수 — 타임아웃 24h · 보관 1개월 · 재개 임계 30분 · 재개 한계 3회 · 재발행 지연 1분")
	void 정책_상수_계약() {
		assertThat(Job.JOB_TIMEOUT).isEqualTo(Duration.ofHours(24));
		assertThat(Job.RETENTION).isEqualTo(Period.ofMonths(1));
		assertThat(Job.STALL_THRESHOLD).isEqualTo(Duration.ofMinutes(30));
		assertThat(Job.REDISPATCH_LIMIT).isEqualTo(3);
		assertThat(WorkRequested.REPUBLISH_DELAY).isEqualTo(Duration.ofMinutes(1));
		assertThat(Job.JOB_TIMEOUT).isNotEqualTo(Job.STALL_THRESHOLD);
		assertThat(WorkRequested.REPUBLISH_DELAY).isLessThan(Job.STALL_THRESHOLD);   // 같으면 아웃박스를 둔 의미가 없다
	}

	/** REQ-156 · REQ-188 · REQ-191 — 상태 불일치(409)와 입력 거절(400)은 다른 코드고, 메시지는 키·링크 없는 고정 문구다 */
	@Test
	@DisplayName("오류 코드 — 001=404(없는=남의) · 002=409(상태 불일치) · 003=400(입력 한계), 메시지에 키·링크 없음")
	void 오류_코드_상태_매핑() {
		assertThat(ErrorCode.SUBTITLE_001.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(ErrorCode.SUBTITLE_002.getStatus()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(ErrorCode.SUBTITLE_003.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);

		assertThat(ErrorCode.SUBTITLE_001.getMessage()).isEqualTo("작업을 찾을 수 없습니다.");
		assertThat(ErrorCode.SUBTITLE_002.getMessage()).isEqualTo("지금 상태에서는 처리할 수 없는 요청입니다.");
		assertThat(ErrorCode.SUBTITLE_003.getMessage()).isEqualTo("원본이 받을 수 있는 한계를 넘었습니다.");
		assertThat(ErrorCode.SUBTITLE_001.getMessage()).doesNotContain("jobs/", "http");
	}

	/** REQ-156 — StorageKey 는 값 자체가 위치라 존재하되 비어 있을 수 없다 */
	@Test
	@DisplayName("저장소 키는 비어 있을 수 없다")
	void 저장소_키는_비어_있을_수_없다() {
		assertThatThrownBy(() -> new StorageKey(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new StorageKey("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	/** REQ-156 — 로그·예외 메시지에 위치 원문이 실리지 않게 toString 에서 강제한다 */
	@Test
	@DisplayName("저장소 키 원문은 toString 에 실리지 않는다")
	void 저장소_키_원문은_toString_에_실리지_않는다() {
		StorageKey key = new StorageKey("jobs/7/source");

		assertThat(key.toString()).doesNotContain("jobs/7/source").isEqualTo("StorageKey(***)");
	}

	/** REQ-115 — 세 위치 모두 서버가 작업 번호로 정한다. 위치가 결정적이어야 존재가 멱등 키가 된다(v3) */
	@Test
	@DisplayName("원본·대본·자막 위치는 전부 작업 번호로 채번된다")
	void 세_위치는_작업_번호로_채번된다() {
		assertThat(StorageKey.sourceOf(JOB).value()).isEqualTo("jobs/7/source");
		assertThat(StorageKey.scriptOf(JOB).value()).isEqualTo("jobs/7/script");
		assertThat(StorageKey.subtitleOf(JOB).value()).isEqualTo("jobs/7/subtitle");
	}
}
