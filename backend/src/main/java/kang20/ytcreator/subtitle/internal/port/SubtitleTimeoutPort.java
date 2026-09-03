package kang20.ytcreator.subtitle.internal.port;

public interface SubtitleTimeoutPort {

	void closeTimedOut();

	void redispatchStalled();
}
