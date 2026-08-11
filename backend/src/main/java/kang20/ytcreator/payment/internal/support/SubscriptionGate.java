package kang20.ytcreator.payment.internal.support;

import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 기간권 개폐·STALE 판정 — 순수 함수, 상태 없음(payment-design.md §4).
 *
 * <p><b>개폐표(§4-3 · iap-essentials §3-5)</b> — 토스 상태 어휘를 그대로 쓰지 않는다:
 * <ul>
 *   <li>{@code ACTIVE} → 연다 (단, 만료 + 유예 1일 경과 시 닫는다 — 웹훅 지연 흡수)</li>
 *   <li>{@code IN_GRACE_PERIOD} → 연다 — 카드 문제로 잠깐 실패한 유료 사용자를 막는 손해가 더 크다.
 *       ⚠️ 만료 경과를 보지 않는다: 웹훅에는 {@code gracePeriodExpiresAt} 이 없어 유예 종료를 우리가
 *       판정할 수 없고, 종료는 토스가 {@code ON_HOLD} 웹훅으로 알린다</li>
 *   <li>{@code ON_HOLD}(유예 종료)·{@code PAUSED}(사용자 의사)·{@code EXPIRED}·{@code REVOKED} → 막는다</li>
 *   <li>구독 이력 없음 → 막는다(횟수권만 본다)</li>
 * </ul>
 *
 * <p><b>STALE(payment.md §4-7-1③)</b> — {@code ACTIVE} 인데 만료 + 유예 1일이 지나도록 다음
 * 웹훅이 없다. 트리거는 <b>시간 경과</b>다 — 웹훅 정본을 받은 뒤의 갱신 유실(매월 반복될 수 있다)도
 * 잡아야 하므로 {@code expiresAtEstimated} 는 판정식에 넣지 않는다(웹훅 수신 여부 <b>진단 정보</b>일
 * 뿐이다). {@code EXPIRED}·{@code REVOKED} 는 웹훅으로 확인된 종료라 STALE 이 아니고(§4-7-1③ 표),
 * {@code IN_GRACE_PERIOD} 는 상태도에 STALE 진입이 없다. STALE 이면 실제 상태를 모르므로
 * 소모를 막고({@code PAY_007}) recheck 를 유도한다.
 */
@Component
public class SubscriptionGate {

	/**
	 * 만료 후 유예 1일(§4-3) — 갱신 웹훅이 늦어도 하루는 열어 둔다.
	 * 경계: {@code expiresAt}·{@code +1일} 은 열림, {@code +1일 1초} 는 닫힘(§10).
	 */
	private static final int GRACE_DAYS = 1;

	/** §4-3 개폐표 + 유예 1일. {@code subscription} null(이력 없음)은 false. */
	public boolean accessible(Subscription subscription, LocalDateTime now) {
		if (subscription == null) {
			return false;
		}
		return switch (subscription.getStatus()) {
			case ACTIVE -> !expired(subscription, now);
			case IN_GRACE_PERIOD -> true;
			case ON_HOLD, PAUSED, EXPIRED, REVOKED -> false;
		};
	}

	/**
	 * payment.md §4-7-1③ — {@code ACTIVE} 인데 만료 + 유예 1일이 지났다 = 상태 미확인.
	 * {@code subscription} null 은 false(재확인할 게 없다). ACTIVE 의 accessible({@code !expired})과
	 * 정확히 상보라 "accessible=true && stale=true" 모순은 생기지 않는다.
	 */
	public boolean stale(Subscription subscription, LocalDateTime now) {
		return subscription != null
			&& subscription.getStatus() == SubscriptionStatus.ACTIVE
			&& expired(subscription, now);
	}

	/** 만료 판정 — 유예 1일을 포함해 accessible/stale 이 같은 경계를 쓴다(판정 어긋남 방지). */
	private boolean expired(Subscription subscription, LocalDateTime now) {
		return subscription.getExpiresAt() != null
			&& now.isAfter(subscription.getExpiresAt().plusDays(GRACE_DAYS));
	}
}
