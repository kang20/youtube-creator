package kang20.ytcreator.payment.internal.service.support;

import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import kang20.ytcreator.payment.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 기간권 개폐·STALE 판정 단위 (payment-design.md §10 {@code SubscriptionGateTest}).
 *
 * <p>덮는 것: payment.md §4-3 개폐표 6종 전수 · §4-7-1⑤ 유예 1일 경계값 ·
 * §4-7-1③ STALE 판정 · 구독 이력 없음.
 *
 * <p>개폐표 정본은 payment.md §4-3(= iap-essentials §3-5): ACTIVE·IN_GRACE_PERIOD 연다 /
 * ON_HOLD·PAUSED·EXPIRED·REVOKED·이력 없음 막는다.
 */
class SubscriptionGateTest {

	private static final LocalDateTime NOW = PaymentFixture.BASE_TIME;

	private final SubscriptionGate gate = new SubscriptionGate();

	/** 추정 만료(estimated=true — 웹훅 미수신)의 ACTIVE 구독. */
	private static Subscription estimatedActive(LocalDateTime expiresAt) {
		return new Subscription(PaymentFixture.uniqueOrder("gate"), PaymentFixture.uniqueUser(), expiresAt);
	}

	/** 웹훅 정본을 받은 구독 — status·expiresAt 을 웹훅 반영 경로로 세팅한다(estimated=false). */
	private static Subscription confirmed(SubscriptionStatus status, LocalDateTime expiresAt) {
		Subscription subscription = estimatedActive(NOW.plusDays(31));
		subscription.applyWebhook(status, expiresAt, true, NOW.minusDays(1));
		return subscription;
	}

	@Nested
	@DisplayName("개폐표 — payment.md §4-3 6종 전수")
	class 개폐표 {

		/** payment.md §4-3 — ACTIVE 는 연다 */
		@Test
		@DisplayName("ACTIVE + 만료 전이면 연다")
		void active_는_연다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.ACTIVE, NOW.plusDays(10)), NOW)).isTrue();
		}

		/** payment.md §4-3 ✅-3 — 카드 문제로 잠깐 실패한 유료 사용자를 막는 손해가 더 크다 */
		@Test
		@DisplayName("IN_GRACE_PERIOD 는 연다")
		void 유예는_연다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.IN_GRACE_PERIOD, NOW.plusDays(10)), NOW))
				.isTrue();
		}

		/**
		 * round-1-dev 판단 1 — IN_GRACE_PERIOD 는 만료 경과를 보지 않는다. 웹훅에
		 * {@code gracePeriodExpiresAt} 이 없어(payment.md §5-5 표 "웹훅만으로는 절대 채울 수 없다")
		 * 유예 종료를 우리가 판정할 수 없고, 종료는 토스가 ON_HOLD 웹훅으로 알린다.
		 * 만료 판정을 적용하면 ✅-3 "유예는 연다"가 사실상 무효가 된다.
		 */
		@Test
		@DisplayName("IN_GRACE_PERIOD 는 expiresAt 이 지났어도 연다 — 유예 종료는 ON_HOLD 웹훅이 알린다")
		void 유예는_만료_경과를_보지_않는다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.IN_GRACE_PERIOD, NOW.minusDays(10)), NOW))
				.isTrue();
		}

		/** payment.md §4-3 ✅-3 — 유예가 끝난 상태 */
		@Test
		@DisplayName("ON_HOLD 는 막는다")
		void 보류는_막는다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.ON_HOLD, NOW.plusDays(10)), NOW)).isFalse();
		}

		/** payment.md §4-3 ✅-3 — 사용자 의사 */
		@Test
		@DisplayName("PAUSED 는 막는다")
		void 일시정지는_막는다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.PAUSED, NOW.plusDays(10)), NOW)).isFalse();
		}

		/** payment.md §4-3 — 웹훅으로 확인된 종료 */
		@Test
		@DisplayName("EXPIRED · REVOKED 는 막는다")
		void 종료는_막는다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.EXPIRED, NOW.plusDays(10)), NOW)).isFalse();
			assertThat(gate.accessible(confirmed(SubscriptionStatus.REVOKED, NOW.plusDays(10)), NOW)).isFalse();
		}

		/** payment.md §4-3 — 구독 이력 없음은 막는다(횟수권만 본다). stale 도 아니다 */
		@Test
		@DisplayName("구독 이력이 없으면 막고, STALE 도 아니다")
		void 이력_없음은_막는다() {
			assertThat(gate.accessible(null, NOW)).isFalse();
			assertThat(gate.stale(null, NOW)).isFalse();
		}
	}

	@Nested
	@DisplayName("유예 1일 경계 — payment.md §4-7-1⑤ 'now ≤ expiresAt + 1일'")
	class 유예_경계 {

		/** §4-7-1⑤ — expiresAt 정각은 열림 */
		@Test
		@DisplayName("expiresAt 정각은 열려 있다")
		void 만료_정각은_열림() {
			assertThat(gate.accessible(estimatedActive(NOW), NOW)).isTrue();
		}

		/** §4-7-1⑤ 경계(설계서 §10) — +1일 정각까지 열림 */
		@Test
		@DisplayName("expiresAt + 1일 정각까지 열려 있다")
		void 만료_1일_후_정각은_열림() {
			assertThat(gate.accessible(estimatedActive(NOW.minusDays(1)), NOW)).isTrue();
		}

		/** §4-7-1⑤ 경계(설계서 §10) — +1일 1초는 닫힘 */
		@Test
		@DisplayName("expiresAt + 1일 1초부터 닫힌다")
		void 만료_1일_1초_후는_닫힘() {
			assertThat(gate.accessible(estimatedActive(NOW.minusDays(1).minusSeconds(1)), NOW)).isFalse();
		}

		/** §4-7-1⑤ — 유예 1일은 웹훅 정본 expiresAt 에도 동일 적용된다("양쪽에 동일 적용") */
		@Test
		@DisplayName("웹훅 정본 expiresAt 에도 유예 1일이 동일 적용된다")
		void 정본에도_유예가_적용된다() {
			assertThat(gate.accessible(confirmed(SubscriptionStatus.ACTIVE, NOW.minusDays(1)), NOW)).isTrue();
			assertThat(gate.accessible(
					confirmed(SubscriptionStatus.ACTIVE, NOW.minusDays(1).minusSeconds(1)), NOW))
				.isFalse();
		}

		/** 방어 계약 — 만료 정보가 없으면 만료로 판정하지 않는다(ACTIVE 는 열린 채, STALE 아님) */
		@Test
		@DisplayName("expiresAt 이 없는 구독은 만료로 보지 않는다")
		void 만료_정보_없음_방어() {
			Subscription subscription = estimatedActive(null);

			assertThat(gate.accessible(subscription, NOW)).isTrue();
			assertThat(gate.stale(subscription, NOW)).isFalse();
		}
	}

	@Nested
	@DisplayName("STALE — payment.md §4-7-1③ '만료 시각이 지났는데 웹훅이 오지 않았다'")
	class STALE_판정 {

		/** §4-7-1③ — 추정 만료 + 유예 경과 = 상태 미확인. §6-7 시퀀스 ②의 재현이다 */
		@Test
		@DisplayName("추정값(웹훅 미수신)이 만료 + 유예를 지나면 STALE 이다")
		void 추정값_만료는_STALE() {
			Subscription subscription = estimatedActive(NOW.minusDays(1).minusSeconds(1));

			assertThat(gate.stale(subscription, NOW)).isTrue();
			assertThat(gate.accessible(subscription, NOW)).isFalse();
		}

		/** §4-7-1③ — 만료 전에는 추정값이어도 STALE 이 아니다(31일간 정상 동작 — §4-7-1④) */
		@Test
		@DisplayName("만료 전에는 추정값이어도 STALE 이 아니다")
		void 만료_전에는_STALE_아님() {
			assertThat(gate.stale(estimatedActive(NOW.plusDays(10)), NOW)).isFalse();
		}

		/** §4-7-1⑤ — STALE 진입 경계는 accessible 폐쇄 경계와 같다(+1일 정각까지는 아직 열림) */
		@Test
		@DisplayName("STALE 진입 경계는 accessible 과 같은 +1일이다")
		void STALE_경계는_유예와_같다() {
			assertThat(gate.stale(estimatedActive(NOW.minusDays(1)), NOW)).isFalse();
			assertThat(gate.stale(estimatedActive(NOW.minusDays(1).minusSeconds(1)), NOW)).isTrue();
		}

		/**
		 * 🔴 payment.md §4-7-1③ — STALE 의 트리거는 <b>시간 경과</b>이지 추정값 여부가 아니다.
		 *
		 * <p>웹훅 정본을 받은(estimated=false) ACTIVE 구독도 그 만료 + 유예가 지나도록
		 * 다음 웹훅(RENEWED/EXPIRED)이 오지 않으면 "만료인지 갱신인지 모른다" — 정확히 §4-7-1③의
		 * STALE 정의다. §4-7-1① 이 문제로 규정한 "갱신 유실"은 최초 갱신에만 일어나는 것이 아니라
		 * <b>매월 반복</b>되고(2회차 갱신 유실), §4-7-1⑧ⓒ의 "recheck 무한 반복" 유보는 STALE
		 * 재진입이 가능함을 전제한다. 여기서 stale=false 면 사용자는 {@code accessible=false,
		 * subscriptionStale=false, status=ACTIVE} 라는 payment.md §4-7-1③ 표에 존재하지 않는
		 * 상태를 받고, reserve 는 PAY_007 대신 PAY_001(결제 유도)로 흘러 §6-7 이 금지한
		 * "이미 낸 사람에게 결제 유도" 화면이 된다.
		 */
		@Test
		@DisplayName("웹훅 정본을 받은 ACTIVE 구독도 만료 + 유예가 지나면 STALE 이다 — 2회차 갱신 유실")
		void 정본_만료도_STALE() {
			Subscription subscription =
				confirmed(SubscriptionStatus.ACTIVE, NOW.minusDays(1).minusSeconds(1));

			assertThat(gate.stale(subscription, NOW))
				.as("payment.md §4-7-1③ — ACTIVE 인데 만료+유예 경과 = STALE. 추정값 조건을 걸면"
					+ " 웹훅을 한 번이라도 받은 구독은 영원히 recheck 를 못 탄다")
				.isTrue();
			assertThat(gate.accessible(subscription, NOW)).isFalse();
		}

		/** §4-7-1③ — 웹훅으로 확인된 종료(EXPIRED·REVOKED)는 STALE 이 아니라 그냥 닫힘이다 */
		@Test
		@DisplayName("웹훅으로 확인된 종료 상태는 STALE 이 아니다")
		void 확인된_종료는_STALE_아님() {
			assertThat(gate.stale(confirmed(SubscriptionStatus.EXPIRED, NOW.minusDays(40)), NOW)).isFalse();
			assertThat(gate.stale(confirmed(SubscriptionStatus.REVOKED, NOW.minusDays(40)), NOW)).isFalse();
		}
	}
}
