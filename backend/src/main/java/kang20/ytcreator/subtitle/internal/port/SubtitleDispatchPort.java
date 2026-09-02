package kang20.ytcreator.subtitle.internal.port;

import kang20.ytcreator.subtitle.internal.entity.WorkRequested;

/**
 * 아웃박스에서 나온 워커 의뢰를 큐로 넘기는 진입 — 부르는 것은 아웃박스 리스너와 재발행 배치뿐이다.
 * 넘기기에 실패하면 예외로 올려 발행이 미완료로 남게 한다 — 재시도는 재발행의 몫이다.
 */
public interface SubtitleDispatchPort {

	void dispatch(WorkRequested requested);

	/** 넘기지 못한 채 REPUBLISH_DELAY 를 넘긴 의뢰를 다시 넘긴다(폴링 퍼블리셔). 두 번 넘어가도 산출물이 한 벌인 것은 워커 몫이다. */
	void republishUndelivered();
}
