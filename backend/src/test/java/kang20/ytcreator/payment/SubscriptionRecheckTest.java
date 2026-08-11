package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;
import kang20.ytcreator.payment.internal.Subscription;
import kang20.ytcreator.payment.internal.SubscriptionRepository;
import kang20.ytcreator.payment.internal.SubscriptionStatus;
import kang20.ytcreator.payment.internal.TossOrderClient;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * §4-7-1⑥⑦ STALE 해소(recheck) — payment-design.md §5-5 · §10 {@code SubscriptionRecheckTest}.
 *
 * <p>덮는 것: STALE → recheck → 게이트 재개방 · <b>{@code lastWebhookOccurredAt} 불변</b>(⑦ —
 * 이 설계의 급소) · recheck 이후 도착한 과거 웹훅의 정상 반영(웹훅 정본 위계) · STALE 아닐 때
 * 멱등 200 · 이력 없음 PAY_004 · 미지 status COMMON_001 · 종료 상태 확정 · KST 환산 저장(✅-7·§12-4).
 *
 * <p>🔴 요청 본문은 클라 값이다(§4-7-1⑧ⓐ — 알고 여는 구멍, §12-3 사용자 결정 "일단 그대로 신뢰").
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, SubscriptionRecheckTest.MovableClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU,
	"ytcreator.payment.webhook.username=" + PaymentFixture.WEBHOOK_USER,
	"ytcreator.payment.webhook.password=" + PaymentFixture.WEBHOOK_PASSWORD
})
class SubscriptionRecheckTest {

	@TestConfiguration
	static class MovableClockConfig {
		static final MutableClock CLOCK = new MutableClock(PaymentFixture.BASE_TIME);

		@Bean
		Clock clock() {
			return CLOCK;
		}
	}

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void clean() {
		subscriptionRepository.deleteAll();
		MovableClockConfig.CLOCK.setTo(PaymentFixture.BASE_TIME);
	}

	/** 최초 지급 직후 상태(ACTIVE·추정 만료 +31일·웹훅 미수신)의 구독. */
	private Subscription subscribed(UserId user, String orderId) {
		return subscriptionRepository.saveAndFlush(
			new Subscription(orderId, user, PaymentFixture.BASE_TIME.plusDays(31)));
	}

	/** §6-7 ② — 갱신 웹훅 유실 상태 재현: 추정 만료 + 유예 1일 경과 지점으로 시간을 옮긴다. */
	private void advancePastEstimatedExpiry() {
		MovableClockConfig.CLOCK.setTo(PaymentFixture.BASE_TIME.plusDays(33));
	}

	private static SubscriptionSnapshot clientSays(String status, LocalDateTime expiresAtKst, boolean autoRenew) {
		OffsetDateTime offset = expiresAtKst == null ? null : expiresAtKst.atOffset(ZoneOffset.ofHours(9));
		return new SubscriptionSnapshot(status, offset, autoRenew);
	}

	// ── §4-7-1⑥ — STALE 해소 ────────────────────────────────────────────

	/** §6-7 ③ — STALE 에서 클라가 ACTIVE 스냅샷을 보내면 게이트가 재개방된다 */
	@Test
	@DisplayName("STALE 구독은 recheck 로 게이트가 재개방된다")
	void STALE_해소() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-open");
		subscribed(user, orderId);
		advancePastEstimatedExpiry();
		assertThat(paymentService.entitlementOf(user).subscriptionStale()).isTrue();

		LocalDateTime renewedExpiry = PaymentFixture.BASE_TIME.plusDays(61);
		EntitlementView result = paymentService.recheck(user, clientSays("ACTIVE", renewedExpiry, true));

		assertThat(result.accessible()).as("§6-7 — 반영 후 게이트 재개방").isTrue();
		assertThat(result.subscriptionStale()).isFalse();

		Subscription subscription = subscriptionRepository.findByOrderId(orderId).orElseThrow();
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(renewedExpiry);
		assertThat(subscription.isExpiresAtEstimated()).isFalse();
	}

	/**
	 * 🔴 §4-7-1⑦ — <b>recheck 는 {@code lastWebhookOccurredAt} 을 갱신하지 않는다.</b>
	 * 갱신하면 뒤늦게 도착한 웹훅이 "과거 이벤트"로 버려져 클라가 보낸 값이 영구히 정본이 된다 —
	 * 웹훅이 정본, recheck 는 임시 보정이라는 위계의 실체다(§5-5 급소).
	 */
	@Test
	@DisplayName("recheck 는 웹훅 순서 축(lastWebhookOccurredAt)을 건드리지 않는다")
	void 순서_축_불간섭() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-axis");
		subscribed(user, orderId);
		advancePastEstimatedExpiry();

		paymentService.recheck(user, clientSays("ACTIVE", PaymentFixture.BASE_TIME.plusDays(61), true));

		assertThat(subscriptionRepository.findByOrderId(orderId).orElseThrow().getLastWebhookOccurredAt())
			.as("§4-7-1⑦ — 웹훅 미수신(null)이면 recheck 후에도 null 이어야 한다")
			.isNull();
	}

	/** §6-7 ④ — 순서 축이 그대로라 뒤늦게 도착한 웹훅이 recheck 결과를 정상적으로 덮는다 */
	@Test
	@DisplayName("recheck 이후 도착한 웹훅이 recheck 결과를 덮는다 — 웹훅이 정본이다")
	void 뒤늦은_웹훅이_recheck_를_덮는다() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-late-hook");
		subscribed(user, orderId);
		advancePastEstimatedExpiry();
		paymentService.recheck(user, clientSays("ACTIVE", PaymentFixture.BASE_TIME.plusDays(61), true));

		// 유실됐던 RENEWED 가 뒤늦게 도착한다 — occurredAt 은 recheck 보다 과거의 실제 갱신 시각
		LocalDateTime webhookOccurredAt = PaymentFixture.BASE_TIME.plusDays(30);
		LocalDateTime webhookExpiry = PaymentFixture.BASE_TIME.plusDays(60);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, webhookOccurredAt, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", webhookExpiry, true)));

		Subscription subscription = subscriptionRepository.findByOrderId(orderId).orElseThrow();
		assertThat(subscription.getExpiresAt())
			.as("§6-7 ④ — 순서 축을 안 건드렸으므로 웹훅이 정상 반영된다(웹훅이 정본)")
			.isEqualTo(webhookExpiry);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(webhookOccurredAt);
	}

	/** §5-7 — 중복 호출은 에러가 아니라 200(§4-5 와 같은 원칙). 이미 해소됐으면 반영 없이 현재 상태 */
	@Test
	@DisplayName("STALE 이 아니면 recheck 는 반영 없이 현재 상태를 돌려준다 — 멱등")
	void STALE_아니면_멱등() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-idem");
		Subscription before = subscribed(user, orderId);
		LocalDateTime keptExpiry = before.getExpiresAt();

		// 클라가 엉뚱한 값을 보내도 STALE 이 아니므로 반영되지 않는다
		EntitlementView result = paymentService.recheck(
			user, clientSays("REVOKED", PaymentFixture.BASE_TIME.minusDays(1), false));

		assertThat(result.accessible()).isTrue();
		Subscription subscription = subscriptionRepository.findByOrderId(orderId).orElseThrow();
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(keptExpiry);
		assertThat(subscription.isExpiresAtEstimated()).as("반영이 없었다는 증거").isTrue();
	}

	/** §5-5 — 구독 이력이 없는데 재확인할 게 없다(PAY_004) */
	@Test
	@DisplayName("구독 이력이 없으면 recheck 는 PAY_004 다")
	void 이력_없음은_PAY_004() {
		assertThatThrownBy(() -> paymentService.recheck(
				PaymentFixture.uniqueUser(), clientSays("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.PAY_004);
	}

	/** round-1-dev 판단 6 — 알 수 없는 status 문자열은 입력 오류(COMMON_001)로 거부한다 */
	@Test
	@DisplayName("알 수 없는 status 문자열은 COMMON_001 이다")
	void 미지_status_는_COMMON_001() {
		UserId user = PaymentFixture.uniqueUser();
		subscribed(user, PaymentFixture.uniqueOrder("recheck-bad-status"));
		advancePastEstimatedExpiry();

		assertThatThrownBy(() -> paymentService.recheck(
				user, clientSays("TOTALLY_NEW", PaymentFixture.BASE_TIME.plusDays(30), true)))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);
	}

	/** §6-7 — recheck 결과가 종료 상태면 EXPIRED 확정. 프론트는 결제 유도로 전환한다 */
	@Test
	@DisplayName("recheck 결과가 종료 상태면 게이트는 닫힌 채 확정된다")
	void 종료_상태_확정() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-expired");
		subscribed(user, orderId);
		advancePastEstimatedExpiry();

		EntitlementView result = paymentService.recheck(
			user, clientSays("EXPIRED", PaymentFixture.BASE_TIME.plusDays(30), false));

		assertThat(result.accessible()).isFalse();
		assertThat(result.subscriptionStale()).as("모름(STALE)이 아니라 확정된 만료다").isFalse();
		assertThat(subscriptionRepository.findByOrderId(orderId).orElseThrow().getStatus())
			.isEqualTo(SubscriptionStatus.EXPIRED);
	}

	/** §5-5 — SDK 가 expiresAt 을 null 로 줄 수 있다(구매 직후 null 사례). 기존 만료를 무로 덮지 않는다 */
	@Test
	@DisplayName("클라 스냅샷의 expiresAt 이 null 이면 기존 만료를 유지한다")
	void 클라_expiresAt_null_방어() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-null-expiry");
		Subscription before = subscribed(user, orderId);
		LocalDateTime keptExpiry = before.getExpiresAt();
		advancePastEstimatedExpiry();

		paymentService.recheck(user, new SubscriptionSnapshot("ACTIVE", null, true));

		Subscription subscription = subscriptionRepository.findByOrderId(orderId).orElseThrow();
		assertThat(subscription.getExpiresAt())
			.as("만료를 null 로 덮으면 만료 판정이 통째로 죽는다")
			.isEqualTo(keptExpiry);
		assertThat(subscription.isExpiresAtEstimated()).isFalse();
	}

	/** ✅-7 · §12-4 — 오프셋 포함 입력을 KST 로 환산해 저장한다. 모호한 시각을 남기지 않는다 */
	@Test
	@DisplayName("recheck 의 expiresAt 은 오프셋을 KST 로 환산해 저장한다")
	void KST_환산_저장() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-utc");
		subscribed(user, orderId);
		advancePastEstimatedExpiry();

		// UTC 자정 = KST 오전 9시
		OffsetDateTime utcMidnight = LocalDateTime.of(2026, 9, 15, 0, 0).atOffset(ZoneOffset.UTC);
		paymentService.recheck(user, new SubscriptionSnapshot("ACTIVE", utcMidnight, true));

		assertThat(subscriptionRepository.findByOrderId(orderId).orElseThrow().getExpiresAt())
			.isEqualTo(LocalDateTime.of(2026, 9, 15, 9, 0));
	}

	/**
	 * 🔴 payment.md §4-7-1②③ — <b>웹훅 정본을 받은 뒤의 갱신 유실도 STALE 로 재진입해야 한다.</b>
	 *
	 * <p>§4-7-1② "웹훅 정상 — current.expiresAt 로 덮어쓴다" 다음에도 ③ "expiresAt + 자체 유예가
	 * 지나도 웹훅이 없으면 STALE 진입"은 계속 적용된다(상태도: ACTIVE → STALE 의 트리거는 시간 경과
	 * 하나다). 갱신 유실은 매월 반복되는 실패이고(§4-7-1① — 30일마다 기회가 온다), 여기서 STALE 이
	 * 아니면 사용자는 recheck 를 안내받지 못한 채(accessible=false·stale=false) 결제 유도로 흘러간다 —
	 * §6-7 이 금지한 화면이다. §4-7-1⑧ⓒ의 "recheck 무한 반복" 유보도 재진입 가능을 전제한다.
	 */
	@Test
	@DisplayName("웹훅 정본을 받은 구독도 그 만료+유예가 지나면 다시 STALE 이고 recheck 로 해소된다")
	void 정본_만료_후에도_STALE_재진입() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("recheck-2nd-loss");
		subscribed(user, orderId);

		// 첫 갱신 웹훅은 정상 도착 — expiresAt 정본 +60일 (§4-7-1②)
		LocalDateTime firstRenewal = PaymentFixture.BASE_TIME.plusDays(30);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, firstRenewal, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(60), true)));

		// 두 번째 갱신 웹훅이 유실된다 — 정본 만료 + 유예 1일 경과 (§4-7-1③)
		MovableClockConfig.CLOCK.setTo(PaymentFixture.BASE_TIME.plusDays(62));

		EntitlementView entitlement = paymentService.entitlementOf(user);
		assertThat(entitlement.subscriptionStale())
			.as("payment.md §4-7-1③ — 만료 시각이 지났는데 웹훅이 없다 = STALE. 추정값 여부와 무관하다")
			.isTrue();
		assertThat(entitlement.accessible()).isFalse();

		// recheck 로 해소 — 실제로는 갱신돼 있었다
		EntitlementView result = paymentService.recheck(
			user, clientSays("ACTIVE", PaymentFixture.BASE_TIME.plusDays(90), true));
		assertThat(result.accessible()).isTrue();
	}
}
