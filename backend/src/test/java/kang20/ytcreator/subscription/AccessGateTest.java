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

class AccessGateTest {

	private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 5, 6, 0, 0, 0);

	private static final LocalDateTime BUFFER_EDGE = EXPIRES_AT.plusDays(3);

	private final AccessGate gate = new AccessGate();

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

	@Test
	@DisplayName("S13 — 완충은 정확히 3일이다 — 2일 뒤는 열려 있고 4일 뒤는 닫혀 있다")
	void 완충은_정확히_3일() {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);

		assertThat(gate.accessible(active, EXPIRES_AT.plusDays(2))).isTrue();
		assertThat(gate.accessible(active, EXPIRES_AT.plusDays(4))).isFalse();
	}

	@Test
	@DisplayName("S13 — IN_GRACE_PERIOD 는 만료가 한참 지나도 열린다")
	void 결제_실패_유예는_만료와_무관하게_열린다() {
		Subscription grace = SubscriptionFixture.of(IN_GRACE_PERIOD, EXPIRES_AT);

		assertThat(gate.accessible(grace, EXPIRES_AT.minusDays(1))).isTrue();
		assertThat(gate.accessible(grace, BUFFER_EDGE.plusYears(1)))
			.as("완충을 한참 넘겨도 열린다 — 우리 완충과 다른 축이다").isTrue();
	}

	@ParameterizedTest(name = "{0} 은 만료 전이라도 닫힌다")
	@EnumSource(value = SubscriptionStatus.class, names = {"ON_HOLD", "PAUSED", "EXPIRED", "REVOKED"})
	@DisplayName("S13 — ON_HOLD·PAUSED·EXPIRED·REVOKED 는 만료 전이라도 닫힌다")
	void 닫히는_상태들(SubscriptionStatus status) {
		Subscription subscription = SubscriptionFixture.of(status, EXPIRES_AT);

		assertThat(gate.accessible(subscription, EXPIRES_AT.minusDays(10))).isFalse();
		assertThat(gate.accessible(subscription, BUFFER_EDGE.plusSeconds(1))).isFalse();
	}

	@Test
	@DisplayName("S13 — 구독 이력이 없으면(null) 닫힌다")
	void 구독_이력_없음() {
		assertThat(gate.accessible(null, EXPIRES_AT)).isFalse();
	}

	@Test
	@DisplayName("S14 — ACTIVE 는 만료+3일 정각까지 미확인이 아니고, 1초 지나면 미확인이다")
	void 미확인_경계() {
		Subscription active = SubscriptionFixture.active(EXPIRES_AT);

		assertThat(gate.stale(active, BUFFER_EDGE))
			.as("경계 정각은 아직 '모른다'가 아니다").isFalse();
		assertThat(gate.stale(active, BUFFER_EDGE.plusSeconds(1)))
			.as("🔴 완충을 넘겨도 다음 웹훅이 없다 = 모른다").isTrue();
	}

	@ParameterizedTest(name = "{0} 은 완충을 넘겨도 미확인이 아니다")
	@EnumSource(value = SubscriptionStatus.class,
		names = {"EXPIRED", "REVOKED", "ON_HOLD", "PAUSED", "IN_GRACE_PERIOD"})
	@DisplayName("S14 — ACTIVE 가 아닌 상태는 미확인이 아니다 — 웹훅으로 확인된 상태다")
	void 활성이_아니면_미확인이_아니다(SubscriptionStatus status) {
		Subscription subscription = SubscriptionFixture.of(status, EXPIRES_AT);

		assertThat(gate.stale(subscription, BUFFER_EDGE.plusYears(1))).isFalse();
	}

	@Test
	@DisplayName("S14 — 구독 이력이 없으면(null) 미확인이 아니다")
	void 이력_없음은_미확인이_아니다() {
		assertThat(gate.stale(null, BUFFER_EDGE.plusYears(1))).isFalse();
	}

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
