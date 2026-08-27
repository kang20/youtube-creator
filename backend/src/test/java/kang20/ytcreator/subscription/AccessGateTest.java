package kang20.ytcreator.subscription;

import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.ACTIVE;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.IN_GRACE_PERIOD;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;
import kang20.ytcreator.subscription.internal.service.support.AccessGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S13·S14·S15 — 이용 게이트의 개폐표와 상태 미확인 판정
 * (payment.md "[이용 가능 판정]" · "[구독 갱신의 유실 상태]" · {@code AccessGate} javadoc).
 *
 * <p>순수 판정이라 스프링을 띄우지 않는다 — 시간은 인자로 들어온다.
 * <b>실시간을 쓰지 않는다</b>: 경계는 고정 시각으로만 말할 수 있다(testing.md "시간·랜덤은 주입").
 *
 * <p>완충은 <b>3일</b>이다(2026-08-14 사용자 확정 — 문서의 🔶 해소).
 */
class AccessGateTest {

	/** 만료 정각. 모든 경계는 이 값 기준이다. */
	private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 5, 6, 0, 0, 0);

	/** 만료 + 완충(3일) 정각 — <b>여기까지는 열린다</b>(경계 포함). */
	private static final LocalDateTime BUFFER_EDGE = EXPIRES_AT.plusDays(3);

	private final AccessGate gate = new AccessGate();

	// ── S13 — accessible 개폐표 ─────────────────────────────────────────

	/**
	 * S13 — 🔴 <b>경계는 포함이다.</b> {@code expiresAt + 3일} 정각까지 열리고 그 다음 순간 닫힌다
	 * ({@code AccessGate.bufferedExpiryPassed} — {@code now.isAfter(expiresAt + BUFFER)}).
	 *
	 * <p>이 경계를 1초라도 잘못 잡으면 <b>돈을 낸 사람이 갱신 웹훅 지연으로 막힌다</b> —
	 * 완충의 존재 이유가 그 비대칭(payment.md "갱신을 놓치면 돈을 낸 사람을 막는다")이다.
	 */
	@Test
	@DisplayName("S13 — ACTIVE 는 만료+3일 정각까지 열리고 1초만 지나도 닫힌다")
	void 만료_완충_경계() {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);

		assertThat(gate.accessible(active, EXPIRES_AT))
			.as("만료 정각은 아직 완충 안이다").isTrue();
		assertThat(gate.accessible(active, BUFFER_EDGE.minusSeconds(1)))
			.as("완충 경계 직전 — 열림").isTrue();
		assertThat(gate.accessible(active, BUFFER_EDGE))
			.as("🔴 expiresAt + 3일 <b>정각</b>은 열림(경계 포함)").isTrue();
		assertThat(gate.accessible(active, BUFFER_EDGE.plusSeconds(1)))
			.as("🔴 +3일 1초는 닫힘 — 여기가 유일한 전환점이다").isFalse();
	}

	/** S13 — 완충은 2일도 4일도 아니다. 3일 고정을 양쪽에서 못 박는다. */
	@Test
	@DisplayName("S13 — 완충은 정확히 3일이다 — 2일 뒤는 열려 있고 4일 뒤는 닫혀 있다")
	void 완충은_정확히_3일() {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);

		assertThat(gate.accessible(active, EXPIRES_AT.plusDays(2))).isTrue();
		assertThat(gate.accessible(active, EXPIRES_AT.plusDays(4))).isFalse();
	}

	/**
	 * S13 — {@code IN_GRACE_PERIOD}(결제 실패 유예)는 <b>만료 경과와 무관하게</b> 열린다.
	 * 유예 종료를 우리가 판정할 수 없기 때문이다 — 웹훅에 {@code gracePeriodExpiresAt} 이 없고,
	 * 종료는 토스가 {@code ON_HOLD} 로 알린다({@code AccessGate} javadoc).
	 */
	@Test
	@DisplayName("S13 — IN_GRACE_PERIOD 는 만료가 한참 지나도 열린다")
	void 결제_실패_유예는_만료와_무관하게_열린다() {
		Subscription grace = SubscriptionFixture.of(IN_GRACE_PERIOD, EXPIRES_AT);

		assertThat(gate.accessible(grace, EXPIRES_AT.minusDays(1))).isTrue();
		assertThat(gate.accessible(grace, BUFFER_EDGE.plusYears(1)))
			.as("완충을 한참 넘겨도 열린다 — 우리 완충과 다른 축이다").isTrue();
	}

	/** S13 — 나머지 네 상태는 만료 전이라도 닫힌다. "어떤 상태인가"와 "열어줄 것인가"는 별개다. */
	@ParameterizedTest(name = "{0} 은 만료 전이라도 닫힌다")
	@EnumSource(value = SubscriptionStatus.class, names = {"ON_HOLD", "PAUSED", "EXPIRED", "REVOKED"})
	@DisplayName("S13 — ON_HOLD·PAUSED·EXPIRED·REVOKED 는 만료 전이라도 닫힌다")
	void 닫히는_상태들(SubscriptionStatus status) {
		Subscription subscription = SubscriptionFixture.of(status, EXPIRES_AT);

		assertThat(gate.accessible(subscription, EXPIRES_AT.minusDays(10))).isFalse();
		assertThat(gate.accessible(subscription, BUFFER_EDGE.plusSeconds(1))).isFalse();
	}

	/** S13 — 구독 이력이 없으면 막는다. 그 사용자는 횟수권만 본다(payment.md "구독 이력이 없으면 횟수권만 본다"). */
	@Test
	@DisplayName("S13 — 구독 이력이 없으면(null) 닫힌다")
	void 구독_이력_없음() {
		assertThat(gate.accessible(null, EXPIRES_AT)).isFalse();
	}

	// ── S14 — stale(상태 미확인) ────────────────────────────────────────

	/**
	 * S14 — 상태 미확인 = <b>활성인데 만료 완충까지 지나도록 다음 웹훅이 없는 것</b>.
	 * accessible 과 <b>같은 경계</b>를 쓴다({@code AccessGate.bufferedExpiryPassed} 공유).
	 */
	@Test
	@DisplayName("S14 — ACTIVE 는 만료+3일 정각까지 미확인이 아니고, 1초 지나면 미확인이다")
	void 미확인_경계() {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);

		assertThat(gate.stale(active, BUFFER_EDGE))
			.as("경계 정각은 아직 '모른다'가 아니다").isFalse();
		assertThat(gate.stale(active, BUFFER_EDGE.plusSeconds(1)))
			.as("🔴 완충을 넘겨도 다음 웹훅이 없다 = 모른다").isTrue();
	}

	/**
	 * S14 — {@code EXPIRED}·{@code REVOKED} 는 <b>웹훅으로 확인된 종료</b>라 미확인이 아니다.
	 * "만료됐다"와 "모른다"를 섞으면 이미 끝난 구독에 재확인을 유도하게 된다.
	 */
	@ParameterizedTest(name = "{0} 은 완충을 넘겨도 미확인이 아니다")
	@EnumSource(value = SubscriptionStatus.class,
		names = {"EXPIRED", "REVOKED", "ON_HOLD", "PAUSED", "IN_GRACE_PERIOD"})
	@DisplayName("S14 — ACTIVE 가 아닌 상태는 미확인이 아니다 — 웹훅으로 확인된 상태다")
	void 활성이_아니면_미확인이_아니다(SubscriptionStatus status) {
		Subscription subscription = SubscriptionFixture.of(status, EXPIRES_AT);

		assertThat(gate.stale(subscription, BUFFER_EDGE.plusYears(1))).isFalse();
	}

	/** S14 — 구독 이력이 없으면 재확인할 것도 없다 — {@code recheck} 의 "이력 없으면 무동작"이 이 값에 걸려 있다. */
	@Test
	@DisplayName("S14 — 구독 이력이 없으면(null) 미확인이 아니다")
	void 이력_없음은_미확인이_아니다() {
		assertThat(gate.stale(null, BUFFER_EDGE.plusYears(1))).isFalse();
	}

	/**
	 * S14 — 🔴 <b>만료값의 출처는 판정에 쓰이지 않는다.</b>
	 *
	 * <p>개시 시 추정한 만료든 이후 덮인 만료든 <b>시간 경과만</b>으로 같은 답이 나와야 한다.
	 * 출처로 분기하면 웹훅을 한 번 받은 뒤의 갱신 유실(매월 반복된다)을 영원히 놓친다
	 * (payment.md "판정 근거는 시간 경과뿐이다").
	 */
	@Test
	@DisplayName("S14 — 추정 만료든 덮인 만료든 판정이 같다 — 만료값의 출처로 분기하지 않는다")
	void 만료값의_출처는_판정에_쓰이지_않는다() {
		Subscription estimated = SubscriptionFixture.active(EXPIRES_AT);
		Subscription confirmed = SubscriptionFixture.of(ACTIVE, EXPIRES_AT);

		LocalDateTime afterBuffer = BUFFER_EDGE.plusSeconds(1);
		assertThat(gate.stale(estimated, afterBuffer)).isEqualTo(gate.stale(confirmed, afterBuffer));
		assertThat(gate.accessible(estimated, afterBuffer)).isEqualTo(gate.accessible(confirmed, afterBuffer));
		assertThat(gate.stale(confirmed, afterBuffer))
			.as("🔴 웹훅을 받은 뒤의 갱신 유실도 잡혀야 한다").isTrue();
	}

	// ── S15 — 두 판정이 모순되지 않는다 ─────────────────────────────────

	/**
	 * S15 — 🔴 <b>"열려 있는데 미확인"이 어떤 상태·어떤 시각에도 나오지 않는다.</b>
	 *
	 * <p>두 판정이 다른 경계를 쓰면 소모는 막히는데 화면은 "이용 가능"으로 보이는 구간이 생긴다.
	 * 경계 근처를 초 단위로 훑어 모든 상태에서 확인한다.
	 */
	@ParameterizedTest(name = "{0} — 경계 근처 어디서도 열림과 미확인이 동시에 성립하지 않는다")
	@EnumSource(SubscriptionStatus.class)
	@DisplayName("S15 — accessible 과 stale 이 동시에 참인 구간이 없다")
	void 모순_상태_부재(SubscriptionStatus status) {
		Subscription subscription = SubscriptionFixture.of(status, EXPIRES_AT);

		for (LocalDateTime now : boundaryNeighbourhood()) {
			assertThat(gate.accessible(subscription, now) && gate.stale(subscription, now))
				.as("%s / %s — 열려 있는데 미확인이면 프론트가 모순된 화면을 그린다", status, now)
				.isFalse();
		}
	}

	/** S15 — {@code ACTIVE} 에서 두 판정은 <b>정확히 상보</b>다(같은 경계를 공유하므로). */
	@ParameterizedTest(name = "경계 {0}초 근방")
	@ValueSource(longs = {-86400, -1, 0, 1, 86400})
	@DisplayName("S15 — ACTIVE 에서 accessible 과 stale 은 정확히 상보다")
	void 활성에서는_정확히_상보(long secondsFromEdge) {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);
		LocalDateTime now = BUFFER_EDGE.plusSeconds(secondsFromEdge);

		assertThat(gate.accessible(active, now)).isNotEqualTo(gate.stale(active, now));
	}

	private static List<LocalDateTime> boundaryNeighbourhood() {
		return List.of(
			EXPIRES_AT.minusDays(1), EXPIRES_AT, EXPIRES_AT.plusSeconds(1),
			BUFFER_EDGE.minusSeconds(1), BUFFER_EDGE, BUFFER_EDGE.plusSeconds(1),
			BUFFER_EDGE.plusDays(30));
	}

	/**
	 * {@code AccessGate} 는 상태를 갖지 않는 순수 판정이다 — 호출 순서가 결과를 바꾸면 게이트가
	 * 시간 인자 밖의 무언가를 보고 있다는 뜻이고, 그 순간 경계 테스트가 전부 거짓말이 된다.
	 */
	@Nested
	@DisplayName("게이트는 상태를 갖지 않는다")
	class 상태_없음 {

		@Test
		@DisplayName("같은 입력에 항상 같은 답 — 호출 순서가 결과를 바꾸지 않는다")
		void 순수_판정() {
			Subscription active = SubscriptionFixture.active(EXPIRES_AT);

			assertThat(gate.accessible(active, BUFFER_EDGE)).isTrue();
			assertThat(gate.stale(active, BUFFER_EDGE.plusSeconds(1))).isTrue();
			assertThat(gate.accessible(active, BUFFER_EDGE))
				.as("앞선 호출이 상태를 남기면 여기서 답이 바뀐다").isTrue();
		}
	}
}
