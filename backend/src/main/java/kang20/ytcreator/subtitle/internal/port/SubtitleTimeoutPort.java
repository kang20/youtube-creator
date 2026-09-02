package kang20.ytcreator.subtitle.internal.port;

/** 배치 진입 — 방치 마감과 멈춘 작업 회복. 부르는 것은 스케줄러뿐이다. */
public interface SubtitleTimeoutPort {

	void closeTimedOut();

	void redispatchStalled();
}
