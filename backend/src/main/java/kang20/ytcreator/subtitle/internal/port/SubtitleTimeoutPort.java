package kang20.ytcreator.subtitle.internal.port;

/**
 * 방치 마감의 진입 — 종결이 아닌 상태로 작업 타임아웃(24h)을 넘긴 작업을 실패로 닫는다.
 * 사용자 대기 구간은 ABANDONED(확정), 시스템 구간은 SERVER_FAULT(회복)로 갈린다 —
 * 이 분류 하나가 돈의 방향을 정한다(subtitle-v1 실패 사유).
 */
public interface SubtitleTimeoutPort {

	void closeTimedOut();

	/**
	 * 시스템 구간에서 재개 임계(30분)를 넘겨 멈춘 작업을 <b>멈춘 그 단계로 다시 시킨다</b> —
	 * 다음 단계로 넘기지 않는다. 재개 한계(3회) 초과는 SERVER_FAULT 로 닫고 이용권을 되돌린다.
	 * 사용자 대기 구간(COMPLETED_SCRIPT)은 대상이 아니다 — 거기는 24h 방치 마감이 담당한다.
	 */
	void redispatchStalled();
}
