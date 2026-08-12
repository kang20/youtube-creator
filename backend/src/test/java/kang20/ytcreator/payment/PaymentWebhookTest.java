package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.internal.service.PaymentService;
import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * U9(웹훅 반영) · U10(등록 검증) · U11(진위 검증) · U13(환불·회수) · C5(중복·순서 역전) —
 * payment-design.md §5-4 · §10 {@code PaymentWebhookTest}.
 *
 * <p>⚠️ 구독은 샌드박스가 없어(payment.md §4-9) 페이로드는 <b>우리가 만든 것</b>이다 —
 * 이 테스트는 "우리 규칙이 맞게 도는가"까지만 답하고, 토스와의 실제 맞물림은 실결제가 답한다(§10).
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, PaymentWebhookTest.FixedClockConfig.class})
@TestPropertySource(properties = {
	"ytcreator.payment.one-time.sku=" + PaymentFixture.ONE_TIME_SKU,
	"ytcreator.payment.subscription.sku=" + PaymentFixture.SUBSCRIPTION_SKU,
	"ytcreator.payment.webhook.username=" + PaymentFixture.WEBHOOK_USER,
	"ytcreator.payment.webhook.password=" + PaymentFixture.WEBHOOK_PASSWORD
})
class PaymentWebhookTest {

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return new MutableClock(PaymentFixture.BASE_TIME);
		}
	}

	private static final LocalDateTime OCCURRED = PaymentFixture.BASE_TIME.minusHours(1);

	@Autowired
	private PaymentService paymentService;


	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	@BeforeEach
	void clean() {
		subscriptionRepository.deleteAll();
	}

	/** grant 로 만들어진 것과 같은 초기 상태(ACTIVE·추정 만료·웹훅 미수신)의 구독을 심는다. */
	private Subscription subscribed(UserId user, String orderId) {
		return subscriptionRepository.saveAndFlush(
			new Subscription(orderId, user, PaymentFixture.BASE_TIME.plusDays(31)));
	}

	private Subscription reload(String orderId) {
		return subscriptionRepository.findByOrderId(orderId).orElseThrow();
	}

	// ── U11 — 진위 검증 ─────────────────────────────────────────────────

	/** U11 · R6 — Basic 불일치는 처리하지 않는다(401). 없으면 누구나 위조 웹훅으로 기간권을 켠다 */
	@Test
	@DisplayName("Basic Auth 불일치 웹훅은 AUTH_002 로 거부되고 상태는 그대로다")
	void 위조_웹훅은_거부() {
		String orderId = PaymentFixture.uniqueOrder("hook-forged");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		var forged = PaymentFixture.statusChanged(orderId, OCCURRED, "REVOKED",
			PaymentFixture.snapshot("REVOKED", null, false));

		assertThatThrownBy(() -> paymentService.handleWebhook("Basic d3Jvbmc6Y3JlZHM=", forged))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_002);

		assertThat(reload(orderId).getStatus())
			.as("U11 — 검증 실패는 '처리하지 않는다'다. 반영이 됐다면 위조가 통했다는 뜻")
			.isEqualTo(SubscriptionStatus.ACTIVE);
	}

	/** U11 — 헤더 자체가 없어도 같은 거부다 */
	@Test
	@DisplayName("Authorization 헤더가 없으면 AUTH_002 다")
	void 헤더_없음도_거부() {
		var event = PaymentFixture.registrationVerification(OCCURRED);

		assertThatThrownBy(() -> paymentService.handleWebhook(null, event))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_002);
	}

	// ── U10 — 등록 검증 이벤트 ──────────────────────────────────────────

	/** U10 — 등록 검증 이벤트는 본문 처리 없이 성공으로 끝난다(204 는 컨트롤러 계약). 빠뜨리면 U9 가 통째로 죽는다 */
	@Test
	@DisplayName("등록 검증 이벤트는 예외 없이 수신되고 아무것도 반영하지 않는다")
	void 등록_검증_이벤트() {
		assertThatCode(() -> paymentService.handleWebhook(
				PaymentFixture.WEBHOOK_AUTH_HEADER, PaymentFixture.registrationVerification(OCCURRED)))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count()).isZero();
	}

	// ── ✅-5 — 웹훅으로 구독을 만들지 않는다 ─────────────────────────────

	/**
	 * ✅-5 · §5-4② — 모르는 orderId 는 기록만 하고 무시한다. 위조 웹훅의 최대치를
	 * "없는 구독 생성"에서 "이미 결제한 사람의 상태 흔들기"로 줄이는 3차 방어다(§4-7).
	 * C6(지급↔CREATED 동시)의 "웹훅이 먼저 온 경우"이기도 하다.
	 */
	@Test
	@DisplayName("모르는 orderId 의 웹훅은 무시되고 구독이 만들어지지 않는다")
	void 모르는_주문은_무시() {
		var created = PaymentFixture.statusChanged(
			PaymentFixture.uniqueOrder("hook-unknown"), OCCURRED, "CREATED",
			PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true));

		assertThatCode(() -> paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER, created))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("✅-5 — 기간권 생성은 mTLS 로 검증한 주문(U2)만 거친다")
			.isZero();
	}

	// ── U9 — 반영 ───────────────────────────────────────────────────────

	/** U9 · §4-7-1② — 웹훅이 정본이다. 추정 만료를 덮고 estimated=false, 순서 축이 선다 */
	@Test
	@DisplayName("RENEWED 웹훅은 expiresAt 을 정본으로 덮고 추정 플래그를 내린다")
	void 갱신_반영() {
		String orderId = PaymentFixture.uniqueOrder("hook-renew");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		LocalDateTime confirmedExpiry = PaymentFixture.BASE_TIME.plusDays(30);

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", confirmedExpiry, true)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(confirmedExpiry);
		assertThat(subscription.isExpiresAtEstimated()).as("정본이 왔다(§5-4④)").isFalse();
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(OCCURRED);
	}

	/** U9 — 순방향 웹훅은 연속으로 반영된다. 순서 축이 매번 앞으로 이동한다 */
	@Test
	@DisplayName("더 미래의 웹훅은 연속으로 반영되고 순서 축이 전진한다")
	void 순방향_연속_반영() {
		String orderId = PaymentFixture.uniqueOrder("hook-forward");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)));

		LocalDateTime later = OCCURRED.plusHours(1);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, later, "AUTO_RENEW_DISABLED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), false)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.isAutoRenew()).isFalse();
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(later);
	}

	/**
	 * §5-5 스펙 위반 방어 — {@code occurredAt} 은 필수인데 빠진 페이로드가 오면 순서 판정 없이
	 * 반영하되 순서 축은 무로 덮지 않는다(round-1-dev 판단 7 의 두 번째 방어).
	 */
	@Test
	@DisplayName("occurredAt 이 없는 웹훅도 반영되지만 순서 축을 무로 덮지 않는다")
	void occurredAt_없음_방어() {
		String orderId = PaymentFixture.uniqueOrder("hook-no-occurred");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)));

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, null, "ON_HOLD",
				PaymentFixture.snapshot("ON_HOLD", null, true)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
		assertThat(subscription.getLastWebhookOccurredAt())
			.as("순서 축이 null 로 덮이면 이후 모든 웹훅의 역전 판정이 죽는다")
			.isEqualTo(OCCURRED);
	}

	/** R7 반대편 — previous 가 우리 상태와 일치하면(유실 없음) WARN 이 없어야 한다. status 없는 previous 도 안전 */
	@Test
	@DisplayName("previous 가 일치하면 유실 경고가 없다")
	void previous_일치는_경고_없음() {
		String orderId = PaymentFixture.uniqueOrder("hook-no-gap");
		subscribed(PaymentFixture.uniqueUser(), orderId);   // 우리 상태: ACTIVE

		ListAppender<ILoggingEvent> appender = captureWarnLogs();
		try {
			paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
				PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
					PaymentFixture.snapshot("ACTIVE", null, true),
					PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)));
			// status 가 빠진 previous — 대조 불가는 불일치가 아니다
			paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
				PaymentFixture.statusChanged(orderId, OCCURRED.plusMinutes(1), "EXTENDED",
					PaymentFixture.snapshot(null, null, null),
					PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(31), true)));
		} finally {
			detach(appender);
		}

		assertThat(appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.map(ILoggingEvent::getFormattedMessage))
			.noneMatch(message -> message.contains("previous 불일치"));
	}

	/** C5 · R8 — occurredAt 이 반영분보다 과거면 무시(순서 역전 → 상태 되감김 차단) */
	@Test
	@DisplayName("반영분보다 과거의 웹훅은 무시된다 — 상태가 되감기지 않는다")
	void 순서_역전은_무시() {
		String orderId = PaymentFixture.uniqueOrder("hook-reorder");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		LocalDateTime confirmedExpiry = PaymentFixture.BASE_TIME.plusDays(30);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", confirmedExpiry, true)));

		// 더 과거의 EXPIRED 가 뒤늦게 도착한다
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED.minusHours(2), "EXPIRED",
				PaymentFixture.snapshot("EXPIRED", PaymentFixture.BASE_TIME.minusDays(1), false)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(confirmedExpiry);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(OCCURRED);
	}

	/** C5 — 이벤트 ID 가 없어 같은 occurredAt 재수신(중복)도 무시가 정답이다(§4-7) */
	@Test
	@DisplayName("같은 occurredAt 의 중복 웹훅은 무시된다")
	void 중복_수신은_무시() {
		String orderId = PaymentFixture.uniqueOrder("hook-dup");
		subscribed(PaymentFixture.uniqueUser(), orderId);
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RENEWED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)));

		// 같은 occurredAt 로 다른 내용이 와도 버린다 — 완전한 판별은 불가능하고 수용했다(§6-6)
		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "REVOKED",
				PaymentFixture.snapshot("REVOKED", null, false)));

		assertThat(reload(orderId).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
	}

	/** R7 — previous 가 우리 상태와 다르면 놓친 웹훅이 있다는 뜻. WARN 으로 감지하되 반영은 진행한다 */
	@Test
	@DisplayName("previous 불일치는 WARN 으로 감지하고 반영은 그대로 진행한다")
	void previous_불일치_유실_감지() {
		String orderId = PaymentFixture.uniqueOrder("hook-gap");
		subscribed(PaymentFixture.uniqueUser(), orderId);   // 우리 상태: ACTIVE

		ListAppender<ILoggingEvent> appender = captureWarnLogs();
		try {
			// previous 가 ON_HOLD — 우리가 모르는 사이 ON_HOLD 를 거쳤다(웹훅 유실)
			paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
				PaymentFixture.statusChanged(orderId, OCCURRED, "RECOVERED",
					PaymentFixture.snapshot("ON_HOLD", null, true),
					PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), true)));
		} finally {
			detach(appender);
		}

		assertThat(appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.map(ILoggingEvent::getFormattedMessage))
			.as("R7 — 감지는 확정이다. 로그가 없으면 유실을 영원히 모른다")
			.anyMatch(message -> message.contains("previous 불일치"));

		assertThat(reload(orderId).isExpiresAtEstimated()).as("반영은 진행됐다").isFalse();
	}

	// ── U13 — 환불·회수 ─────────────────────────────────────────────────

	/** U13 · §4-8 — REVOKED 는 즉시 회수. 다음 entitlement 부터 닫힌다 */
	@Test
	@DisplayName("REVOKED 웹훅은 즉시 회수한다")
	void 회수_반영() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("hook-revoke");
		subscribed(user, orderId);

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "REVOKED",
				PaymentFixture.snapshot("REVOKED", null, false)));

		assertThat(reload(orderId).getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
		assertThat(paymentService.entitlementOf(user).accessible())
			.as("U13 — 회수는 게이트에 즉시 반영된다(§6-6)")
			.isFalse();
	}

	/** §4-8 — AUTO_RENEW_DISABLED(해지 예약)는 만료일까지 계속 열어준다. autoRenew 표시만 바뀐다 */
	@Test
	@DisplayName("해지 예약(AUTO_RENEW_DISABLED)은 만료일까지 열려 있고 autoRenew 만 꺼진다")
	void 해지_예약은_만료일까지_유지() {
		UserId user = PaymentFixture.uniqueUser();
		String orderId = PaymentFixture.uniqueOrder("hook-cancel");
		subscribed(user, orderId);
		LocalDateTime expiry = PaymentFixture.BASE_TIME.plusDays(15);

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "AUTO_RENEW_DISABLED",
				PaymentFixture.snapshot("ACTIVE", expiry, false)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
		assertThat(subscription.isAutoRenew()).isFalse();
		assertThat(paymentService.entitlementOf(user).accessible())
			.as("§4-8 — 해지 예약은 회수가 아니다. 만료일까지 연다")
			.isTrue();
		assertThat(paymentService.entitlementOf(user).subscription().autoRenew())
			.as("프론트의 '해지 예약됨' 표시 근거(§5-3)")
			.isFalse();
	}

	/**
	 * §4-7 · §5-4 — ❗{@code changeReason} 만으로 판정 금지. {@code RESTARTED} 인데
	 * {@code autoRenew=false} 인 사례가 보고됐다 — 반영은 {@code current} 세 값으로만 한다.
	 */
	@Test
	@DisplayName("RESTARTED 여도 current.autoRenew=false 면 false 를 따른다 — changeReason 으로 판정하지 않는다")
	void RESTARTED_는_current_를_따른다() {
		String orderId = PaymentFixture.uniqueOrder("hook-restart");
		subscribed(PaymentFixture.uniqueUser(), orderId);

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "RESTARTED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(30), false)));

		assertThat(reload(orderId).isAutoRenew())
			.as("payment.md §4-7 — RESTARTED 인데 autoRenew=false 사례 보고됨. current 가 정본")
			.isFalse();
	}

	// ── 방어 — 스펙 밖 페이로드 ─────────────────────────────────────────

	/** round-1-dev 판단 7 — current.expiresAt=null(플랫폼 "null 가능")이면 기존 값을 유지한다 */
	@Test
	@DisplayName("current.expiresAt 이 null 이면 기존 만료를 유지한다 — 정본을 무로 덮지 않는다")
	void expiresAt_null_방어() {
		String orderId = PaymentFixture.uniqueOrder("hook-null-expiry");
		Subscription before = subscribed(PaymentFixture.uniqueUser(), orderId);
		LocalDateTime keptExpiry = before.getExpiresAt();

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "ON_HOLD",
				PaymentFixture.snapshot("ON_HOLD", null, true)));

		Subscription subscription = reload(orderId);
		assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
		assertThat(subscription.getExpiresAt())
			.as("만료를 null 로 덮으면 만료 판정이 통째로 죽는다")
			.isEqualTo(keptExpiry);
	}

	/** autoRenew 가 생략된 스냅샷 — 기존 값을 유지한다 */
	@Test
	@DisplayName("current.autoRenew 가 null 이면 기존 값을 유지한다")
	void autoRenew_null_방어() {
		String orderId = PaymentFixture.uniqueOrder("hook-null-renew");
		subscribed(PaymentFixture.uniqueUser(), orderId);   // autoRenew=true 로 태어난다

		paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
			PaymentFixture.statusChanged(orderId, OCCURRED, "EXTENDED",
				PaymentFixture.snapshot("ACTIVE", PaymentFixture.BASE_TIME.plusDays(40), null)));

		assertThat(reload(orderId).isAutoRenew()).isTrue();
	}

	/**
	 * §5-4④ — <b>반영 실패가 수신 성공을 막지 않는다.</b> 재전송 정책이 없어 재시도에 기댈 수 없다.
	 * 미지의 status 문자열(미문서화 값 추가)로 반영이 죽어도 예외가 새어나가면 안 된다 —
	 * 컨트롤러까지 전파되면 204 계약(§5-5)이 깨진다.
	 */
	@Test
	@DisplayName("미지의 current.status 로 반영이 실패해도 예외가 전파되지 않고 상태는 그대로다")
	void 반영_실패도_수신은_성공() {
		String orderId = PaymentFixture.uniqueOrder("hook-unknown-status");
		subscribed(PaymentFixture.uniqueUser(), orderId);

		assertThatCode(() -> paymentService.handleWebhook(PaymentFixture.WEBHOOK_AUTH_HEADER,
				PaymentFixture.statusChanged(orderId, OCCURRED, "SOMETHING_NEW",
					PaymentFixture.snapshot("BRAND_NEW_STATUS", null, true))))
			.as("payment.md §4-7 — 반영 실패해도 204. 여기서 던지면 토스가 볼 응답이 5xx 가 된다")
			.doesNotThrowAnyException();

		assertThat(reload(orderId).getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
	}

	// ── 로그 캡처 ───────────────────────────────────────────────────────

	private ListAppender<ILoggingEvent> captureWarnLogs() {
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
			LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		return appender;
	}

	private void detach(ListAppender<ILoggingEvent> appender) {
		ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
			LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		root.detachAppender(appender);
		appender.stop();
	}
}
