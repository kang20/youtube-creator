package kang20.ytcreator.base;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 테스트 전용 가변 시계 — {@code Clock.fixed} 는 만들고 나면 시간을 옮길 수 없어
 * "만료가 지났다"(STALE·유예 경계) 시나리오를 한 컨텍스트에서 재현할 수 없다.
 *
 * <p>시간·랜덤은 주입한다는 규칙(docs/rule/testing.md 작성 원칙 4)의 테스트 쪽 짝이다 —
 * 프로덕션은 {@code Clock} 빈을 주입받으므로 이 시계를 대신 꽂으면 시간이 고정·이동 가능해진다.
 */
public final class MutableClock extends Clock {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private Instant instant;

	public MutableClock(LocalDateTime initial) {
		this.instant = initial.atZone(KST).toInstant();
	}

	/** 시계를 지정 시각(KST 해석)으로 옮긴다. */
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
