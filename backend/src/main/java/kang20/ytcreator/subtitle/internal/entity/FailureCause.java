package kang20.ytcreator.subtitle.internal.entity;

/**
 * 이 값 하나가 이용권을 되돌릴지를 정한다 — {@link #SERVER_FAULT} 는 release, {@link #ABANDONED} 는 commit.
 * 입력 반려(USER_FAULT)는 여기 없다 — 작업이 열리기 전에 거절되는 것이라 실패 사유가 아니라 오류 코드다.
 */
public enum FailureCause {
	SERVER_FAULT,
	ABANDONED
}
