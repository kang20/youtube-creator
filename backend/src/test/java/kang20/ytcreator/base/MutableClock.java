package kang20.ytcreator.base;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class MutableClock extends Clock {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private Instant instant;

	public MutableClock(LocalDateTime initial) {
		this.instant = initial.atZone(KST).toInstant();
	}

	public void setTo(LocalDateTime dateTime) {
		this.instant = dateTime.atZone(KST).toInstant();
	}

	@Override
	public ZoneId getZone() {
		return KST;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("테스트 시계는 KST 고정이다");
	}

	@Override
	public Instant instant() {
		return instant;
	}
}
