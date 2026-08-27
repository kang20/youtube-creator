package kang20.ytcreator.subscription.internal.service.support;

import java.time.Duration;
import java.time.LocalDateTime;

import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;

/**
 * 이용 게이트 — 기간권이 살아 있는지(accessible), 구독 상태가 미확인 구간인지(stale) 판정한다
 * (new-domain/payment.md 이용 게이트).
 *
 * <p><b>상태를 갖지 않는 순수 판정이다.</b> 저장소를 알지 못하고 시간도 인자로 받는다.
 *
 * <p>🔴 <b>토스가 주는 구독 상태를 그대로 게이트에 쓰지 않는다.</b> "어떤 상태인가"와 "열어줄
 * 것인가"는 별개다. 개폐표:
 * <ul>
 *   <li>{@code ACTIVE} → <b>연다.</b> 단 만료 시각 + {@link #EXPIRY_BUFFER} 가 지나면 닫는다</li>
 *   <li>{@code IN_GRACE_PERIOD}(결제 실패 유예) → <b>연다.</b> 카드 문제로 잠깐 실패한 유료 사용자를
 *       막는 손해가 더 크다. ⚠️ 만료 경과를 보지 않는다 — 웹훅에는 {@code gracePeriodExpiresAt} 이
 *       없어 유예 종료를 우리가 판정할 수 없고, 종료는 토스가 {@code ON_HOLD} 웹훅으로 알린다</li>
 *   <li>{@code ON_HOLD}(유예 종료) · {@code PAUSED}(사용자 일시정지) · {@code EXPIRED} ·
 *       {@code REVOKED} → <b>막는다</b></li>
 *   <li>구독 이력 없음 → <b>막는다.</b> 그 경우 횟수권만 본다</li>
 * </ul>
 *
 * <p>🔴 <b>{@code accessible} 과 {@code stale} 은 같은 경계를 쓴다.</b> 기준이 어긋나면 "열려 있는데
 * 미확인"이라는 모순 상태가 생긴다 — {@code ACTIVE} 에서 두 판정은 정확히 상보다.
 *
 * <p>⚠️ <b>웹훅을 받았는지를 판정에 넣지 않는다.</b> 넣으면 웹훅을 한 번 받은 뒤의 갱신 유실(매월
 * 반복될 수 있다)을 잡지 못한다 — 판정 근거는 <b>시간 경과뿐</b>이다.
 */
@Support
public class AccessGate {

	/**
	 * 만료 완충 <b>3일</b> — 만료 시각이 지나도 이만큼은 열어 둔다.
	 *
	 * <p>🔴 <b>결제 실패 유예({@link SubscriptionStatus#IN_GRACE_PERIOD})와 다른 것이다.</b>
	 * 앞은 토스가 주는 <b>사실</b>이고, 이것은 갱신 웹훅이 늦을 것을 감안한 <b>우리 정책</b>이다.
	 * 두 유예가 같은 말로 불리면 개폐표를 읽을 수 없어서 이름을 갈랐다.
	 *
	 * <p><b>왜 3일인가</b>: 유실의 손해가 방향에 따라 다르기 때문이다. 만료를 놓치면 안 낸 사람에게
	 * 며칠 더 열어주는 것으로 끝나지만, <b>갱신을 놓치면 돈을 낸 사람을 막는다.</b> 뒤쪽이 훨씬
	 * 나쁘고, 완충은 이 비대칭을 기준으로 정한다.
	 */
	private static final Duration EXPIRY_BUFFER = Duration.ofDays(3);

	/**
	 * 기간권이 살아 있는가 — 프론트가 보는 "이용 가능"의 구독 쪽 절반이다.
	 * (나머지 절반인 횟수권 잔량과의 합성은 이용권 읽기 모델의 몫이다.)
	 *
	 * @param subscription 구독 이력이 없으면 {@code null} — 막는다
	 */
	public boolean accessible(Subscription subscription, LocalDateTime now) {
		if (subscription == null) {
			return false;
		}
		return switch (subscription.getStatus()) {
			case ACTIVE -> !bufferedExpiryPassed(subscription, now);
			case IN_GRACE_PERIOD -> true;
			case ON_HOLD, PAUSED, EXPIRED, REVOKED -> false;
		};
	}

	/**
	 * 상태 미확인인가 — <b>활성인데 만료 완충까지 지나도록 다음 웹훅이 없는 것</b>이다.
	 * "만료됐다"가 아니라 <b>"모른다"</b> 이며, 이때는 소모를 막고 재확인을 유도한다.
	 *
	 * <p>⚠️ <b>웹훅을 한 번이라도 받았는지({@code lastWebhookOccurredAt} 유무)로 판정하지 않는다.</b>
	 * 그러면 매월 반복되는 갱신 유실을 잡지 못한다 — 판정 기준은 시간 경과다.
	 *
	 * <p>{@code EXPIRED}·{@code REVOKED} 는 <b>웹훅으로 확인된 종료</b>라 미확인이 아니다.
	 * 구독 이력이 없으면({@code null}) 재확인할 것도 없다.
	 */
	public boolean stale(Subscription subscription, LocalDateTime now) {
		return subscription != null
			&& subscription.getStatus() == SubscriptionStatus.ACTIVE
			&& bufferedExpiryPassed(subscription, now);
	}

	/**
	 * 만료 + 완충이 지났는가. {@code accessible} 과 {@code stale} 이 <b>이 한 메서드</b>를 공유해야
	 * 두 판정의 경계가 어긋날 수 없다.
	 *
	 * <p>경계는 <b>포함</b>이다 — {@code expiresAt + 3일} 정각까지는 열려 있고 그 다음 순간부터 닫힌다.
	 */
	private boolean bufferedExpiryPassed(Subscription subscription, LocalDateTime now) {
		return now.isAfter(subscription.getExpiresAt().plus(EXPIRY_BUFFER));
	}
}
