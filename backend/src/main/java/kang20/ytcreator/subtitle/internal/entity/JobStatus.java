package kang20.ytcreator.subtitle.internal.entity;

import java.util.function.Function;

/**
 * 값 하나가 곧 프론트 화면 분기다 — 이름이 계약이다. 전이의 정본은 subtitle-v1 '작업 진행 상태' 표.
 * 사용자를 기다리는 구간은 {@link #COMPLETED_SCRIPT} 하나뿐이고, 나머지는 전부 시스템이 도는 구간이다.
 */
public enum JobStatus {
	CREATED,
	REQUEST_SCRIPT,
	COMPLETED_SCRIPT,
	CONFIRM_SCRIPT,
	REQUEST_SUBTITLE,
	COMPLETED_SUBTITLE,
	FAILURE
}
