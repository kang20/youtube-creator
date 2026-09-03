package kang20.ytcreator.subscription;

import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.ACTIVE;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.ON_HOLD;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.PAUSED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import kang20.ytcreator.subscription.dto.WebhookEvent;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SubscriptionTest {

	private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 14, 12, 0);

	@Test
	@DisplayName("start — 활성·추정 만료·자동 갱신·웹훅 미수신으로 태어난다")
	void 개시() {
		Subscription subscription = Subscription.start(SubscriptionFixture.ORDER,
			SubscriptionFixture.USER, EXPIRES_AT);

		assertThat(subscription.getStatus()).isEqualTo(ACTIVE);
		assertThat(subscription.getOrderId()).isEqualTo(SubscriptionFixture.ORDER);
		assertThat(subscription.getUserId()).isEqualTo(SubscriptionFixture.USER);
		assertThat(subscription.getExpiresAt()).isEqualTo(EXPIRES_AT);
		assertThat(subscription.isAutoRenew()).isTrue();
		assertThat(subscription.getLastWebhookOccurredAt()).isNull();
	}

	@Test
	@DisplayName("applyClientSnapshot — 상태·만료·자동 갱신만 바꾸고 웹훅 기준값은 손대지 않는다")
	void 클라이언트_스냅샷_반영() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);
		LocalDateTime corrected = LocalDateTime.of(2026, 10, 1, 0, 0);

		subscription.applyClientSnapshot(PAUSED, corrected, false);

		assertThat(subscription.getStatus()).isEqualTo(PAUSED);
		assertThat(subscription.getExpiresAt()).isEqualTo(corrected);
		assertThat(subscription.isAutoRenew()).isFalse();
		assertThat(subscription.getLastWebhookOccurredAt())
			.as("🔴 여기가 채워지면 뒤늦게 온 웹훅이 과거로 취급돼 버려진다").isNull();
	}

	@Test
	@DisplayName("applyClientSnapshot — expiresAt 이 null 이면 기존 만료를 유지한다")
	void 만료가_비면_유지() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);

		subscription.applyClientSnapshot(ON_HOLD, null, true);

		assertThat(subscription.getExpiresAt())
			.as("정본을 무로 덮으면 만료 판정이 통째로 죽는다").isEqualTo(EXPIRES_AT);
		assertThat(subscription.getStatus()).isEqualTo(ON_HOLD);
	}

	@Test
	@DisplayName("applyWebhook — 첫 웹훅은 기준값이 없어 발생 시각과 무관하게 반영된다")
	void 첫_웹훅은_무조건_반영() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);
		LocalDateTime occurredAt = LocalDateTime.of(2020, 1, 1, 0, 0);
		LocalDateTime newExpiry = LocalDateTime.of(2026, 12, 1, 0, 0);

		assertThat(subscription.applyWebhook(PAUSED, snap(newExpiry, false), occurredAt)).isTrue();

		assertThat(subscription.getStatus()).isEqualTo(PAUSED);
		assertThat(subscription.getExpiresAt()).isEqualTo(newExpiry);
		assertThat(subscription.isAutoRenew()).isFalse();
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("applyWebhook — 기준값과 같거나 그보다 과거인 웹훅은 아무것도 바꾸지 않는다")
	void 과거_웹훅은_반영되지_않는다() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);
		LocalDateTime base = LocalDateTime.of(2026, 9, 1, 0, 0);
		subscription.applyWebhook(ACTIVE, snap(EXPIRES_AT, true), base);

		for (LocalDateTime stale : List.of(base, base.minusSeconds(1))) {
			assertThat(subscription.applyWebhook(PAUSED, snap(LocalDateTime.of(2020, 1, 1, 0, 0), false), stale))
				.as("발생 시각 %s", stale).isFalse();
		}
		assertThat(subscription.getStatus()).isEqualTo(ACTIVE);
		assertThat(subscription.getExpiresAt())
			.as("🔴 만료가 되감기면 갱신을 받은 사용자가 막힌다").isEqualTo(EXPIRES_AT);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(base);
	}

	@Test
	@DisplayName("applyWebhook — expiresAt·autoRenew 가 null 이면 기존 값을 유지한다")
	void 비어_온_값은_기존_값을_유지() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);
		subscription.applyWebhook(ACTIVE, snap(EXPIRES_AT, false), LocalDateTime.of(2026, 9, 1, 0, 0));

		subscription.applyWebhook(PAUSED, snap(null, null), LocalDateTime.of(2026, 9, 2, 0, 0));

		assertThat(subscription.getExpiresAt()).isEqualTo(EXPIRES_AT);
		assertThat(subscription.isAutoRenew())
			.as("비어 온 값을 false 로 읽으면 자동 갱신 상태가 조용히 뒤집힌다").isFalse();
		assertThat(subscription.getStatus()).isEqualTo(PAUSED);
	}

	@Test
	@DisplayName("applyWebhook — occurredAt 이 null 이면 반영하되 순서 기준값은 그대로다")
	void 발생시각이_비면_기준값_유지() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);
		LocalDateTime base = LocalDateTime.of(2026, 9, 1, 0, 0);
		subscription.applyWebhook(ACTIVE, snap(EXPIRES_AT, true), base);

		assertThat(subscription.applyWebhook(PAUSED, snap(EXPIRES_AT, true), null)).isTrue();

		assertThat(subscription.getStatus()).isEqualTo(PAUSED);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(base);
	}

	@Test
	@DisplayName("hasMissedWebhook — 직전 상태가 우리 상태와 다르면 유실이다")
	void 유실_판정() {
		Subscription subscription = SubscriptionFixture.active(EXPIRES_AT);   // ACTIVE

		assertThat(subscription.hasMissedWebhook("ON_HOLD")).isTrue();
		assertThat(subscription.hasMissedWebhook("ACTIVE")).isFalse();
	}

	@Test
	@DisplayName("hasMissedWebhook — 직전 상태가 없으면 유실이 아니다")
	void 직전_상태가_없으면_유실이_아니다() {
		assertThat(SubscriptionFixture.active(EXPIRES_AT).hasMissedWebhook(null)).isFalse();
	}

	@Test
	@DisplayName("hasMissedWebhook — 모르는 상태 어휘가 와도 터지지 않고 유실로 읽는다")
	void 모르는_어휘도_감지된다() {
		assertThat(SubscriptionFixture.active(EXPIRES_AT).hasMissedWebhook("TOSS_INVENTED_THIS")).isTrue();
	}

	@Test
	@DisplayName("SubscriptionStatus.from — 6종은 복원되고 모르는 값은 비어서 돌아온다")
	void 상태_어휘_변환() {
		for (SubscriptionStatus status : SubscriptionStatus.values()) {
			assertThat(SubscriptionStatus.from(status.name())).contains(status);
		}
		assertThat(SubscriptionStatus.from("TOSS_INVENTED_THIS")).isEmpty();
		assertThat(SubscriptionStatus.from(null)).isEqualTo(Optional.empty());
		assertThat(SubscriptionStatus.from("active"))
			.as("대소문자를 흘려 받으면 토스 어휘를 그대로 옮긴다는 전제가 깨진다").isEmpty();
	}

	private static WebhookEvent.Snapshot snap(LocalDateTime expiresAt, Boolean autoRenew) {
		return new WebhookEvent.Snapshot(null, true, expiresAt, autoRenew);
	}
}
