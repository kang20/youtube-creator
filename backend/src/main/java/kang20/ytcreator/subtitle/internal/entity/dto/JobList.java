package kang20.ytcreator.subtitle.internal.entity.dto;

import java.time.LocalDateTime;
import java.util.List;
import kang20.ytcreator.subtitle.internal.entity.JobStatus;

/**
 * 작업 목록 읽기 모델 — 조회 시점에 조립한다. 테이블도 애그리거트도 아니다.
 * 만드는 동안 저장소를 부르지 않는다 — 저장소 장애가 복구 장치(목록 화면)로 번지면 안 된다.
 */
public record JobList(List<Item> jobs) {

	public record Item(long jobId, JobStatus status, boolean expired, LocalDateTime createdAt) {
	}
}
