package kang20.ytcreator.subtitle.internal.entity.dto;

import kang20.ytcreator.subtitle.internal.entity.JobStatus;

/**
 * 전이 시도의 결과 — {@code advanced} 가 없으면 확정 재요청·중복 통지에도 산출을 다시 의뢰해
 * 같은 파일이 두 벌 생긴다(subtitle-v1 확정 규칙).
 */
public record TransitionResult(boolean advanced, JobStatus status) {
}
