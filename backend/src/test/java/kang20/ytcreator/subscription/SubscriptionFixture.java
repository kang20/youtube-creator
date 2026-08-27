package kang20.ytcreator.subscription;

import java.time.LocalDateTime;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;

/**
 * 구독 테스트 데이터 표준화(testing.md 작성 원칙 1).
 *
 * <p>구독은 <b>검증된 주문을 거쳐서만</b> 태어나므로({@code SubscriptionGrantPort}) 임의 상태의
 * 엔티티를 만드는 생성자가 없다 — 여기서는 공개 행위 두 개
 * ({@code start()} → {@code applyClientSnapshot()})를 조합해 원하는 상태를 만든다.
 * 판정 테스트가 리플렉션으로 필드를 비트는 것을 막기 위한 자리다.
 */
final class SubscriptionFixture {

	static final OrderId ORDER = new OrderId("sub-fixture-order");

	static final UserId USER = new UserId(7001L);

	private SubscriptionFixture() {
	}

	/**
	 * 갓 태어난 구독 — {@code ACTIVE} · 추정 만료 · 웹훅 미수신.
	 * (payment.md 구독 애그리거트 {@code static start()})
	 */
	static Subscription active(LocalDateTime expiresAt) {
		return Subscription.start(ORDER, USER, expiresAt);
	}

	/**
	 * 임의 상태의 구독. 클라이언트 스냅샷 반영 경로를 거치므로 만료값이 한 번 덮인 상태다 —
	 * 🔴 <b>만료값의 출처가 판정에 쓰이지 않는다</b>는 규칙을 {@code active()} 와 대조해 확인하는 데 쓴다.
	 */
	static Subscription of(SubscriptionStatus status, LocalDateTime expiresAt) {
		Subscription subscription = active(expiresAt);
		subscription.applyClientSnapshot(status, expiresAt, true);
		return subscription;
	}

	/** 상태 변경 웹훅 — 참고자료 ② 페이로드 그대로. */
	static WebhookEvent statusChanged(String rawOrderId, LocalDateTime occurredAt,
			WebhookEvent.Snapshot previous, WebhookEvent.Snapshot current) {
		return new WebhookEvent("subscription.status_changed", "1.0", occurredAt, rawOrderId,
			"test.subscription", "RENEWED", new WebhookEvent.SubscriptionChange(previous, current));
	}

	/** 변경 후 스냅샷. {@code accessGranted} 는 <b>쓰지 않는</b> 필드라 페이로드 모양을 위해서만 채운다. */
	static WebhookEvent.Snapshot snapshot(SubscriptionStatus status, LocalDateTime expiresAt, Boolean autoRenew) {
		return new WebhookEvent.Snapshot(status.name(), true, expiresAt, autoRenew);
	}
}
