package kang20.ytcreator.subscription;

import static kang20.ytcreator.subscription.SubscriptionFixture.snapshot;
import static kang20.ytcreator.subscription.SubscriptionFixture.statusChanged;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.ACTIVE;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.EXPIRED;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.IN_GRACE_PERIOD;
import static kang20.ytcreator.subscription.internal.entity.SubscriptionStatus.PAUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.config.TimeConfig;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.subscription.internal.port.SubscriptionGrantPort;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubscriptionServiceTest.TestClockConfig.class})
@TestPropertySource(properties = "ytcreator.subscription.webhook.secret=" + SubscriptionServiceTest.SECRET)
class SubscriptionServiceTest {

	static final String SECRET = "test-webhook-shared-secret";

	private static final String AUTH_HEADER = "Basic " + SECRET;

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	private static final LocalDateTime ESTIMATED_EXPIRES_AT = FIXED_NOW.plusDays(31);

	@TestConfiguration
	static class TestClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(FIXED_NOW.atZone(TimeConfig.KST).toInstant(), TimeConfig.KST);
		}
	}

	private static final UserId USER = new UserId(5001L);
	private static final OrderId ORDER = new OrderId("sub-order-primary");

	@Autowired
	private SubscriptionGrantPort subscriptionGrant;

	@Autowired
	private SubscriptionStatusPort subscriptionStatus;

	@Autowired
	private SubscriptionRepository subscriptionRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate listenerTx;

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void 구독을_비운다() {
		listenerTx = new TransactionTemplate(transactionManager);
		subscriptionRepository.deleteAll();
		logs = new ListAppender<>();
		logs.start();
		serviceLogger().addAppender(logs);
	}

	@AfterEach
	void 로그_수집을_끝낸다() {
		serviceLogger().detachAppender(logs);
	}

	@Test
	@DisplayName("S1 — 개시하면 ACTIVE · 결제일+31일 추정 만료 · 웹훅 미수신으로 태어난다")
	void 개시() {
		start(USER, ORDER);

		Subscription subscription = theOnlyRow();

		assertThat(subscription.getUserId()).isEqualTo(USER);
		assertThat(subscription.getOrderId())
			.as("🔴 웹훅이 이 값으로 구독을 찾아온다 — 마스킹 값이 들어가면 이후 모든 웹훅이 버려진다")
			.isEqualTo(ORDER);
		assertThat(subscription.getStatus()).isEqualTo(ACTIVE);
		assertThat(subscription.getExpiresAt())
			.as("월 주기 30일 확정(참고자료 ⑥-2) + 여유 1일 — 짧게 잡으면 돈 낸 사람을 막는다")
			.isEqualTo(ESTIMATED_EXPIRES_AT);
		assertThat(subscription.isAutoRenew()).isTrue();
		assertThat(subscription.getLastWebhookOccurredAt())
			.as("웹훅 미수신 — 첫 웹훅은 발생 시각과 무관하게 반영돼야 한다").isNull();
	}

	@Test
	@DisplayName("S6 — 같은 주문의 재지급은 오류가 아니라 멱등 성공이다 — 구독은 한 행뿐")
	void 같은_주문_재지급은_멱등() {
		start(USER, ORDER);

		assertThatCode(() -> start(USER, ORDER))
			.as("재전달된 이벤트가 거부되면 리스너가 영원히 재시도한다").doesNotThrowAnyException();

		assertThat(subscriptionRepository.count()).isEqualTo(1);
		assertThat(theOnlyRow().getExpiresAt())
			.as("재지급이 만료를 다시 밀면 공짜 연장이 된다").isEqualTo(ESTIMATED_EXPIRES_AT);
	}

	@Test
	@DisplayName("S5 — 만료된 구독이 있어도 다른 주문으로 재구독하면 새 계약이 열린다")
	void 재구독은_새_계약을_연다() {
		start(USER, ORDER);
		expire(ORDER);
		OrderId secondOrder = new OrderId("sub-order-second");

		assertThatCode(() -> start(USER, secondOrder))
			.as("🔴 여기서 거부되면 한 번 구독한 사용자는 영영 재구독할 수 없다")
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("구독은 시간축으로 여러 번 생긴다 — 이력이 쌓여야 한다").isEqualTo(2);
		assertThat(subscriptionRepository.findByOrderId(secondOrder)).isPresent();
		assertThat(subscriptionRepository.findByOrderId(ORDER))
			.as("옛 계약의 order_id 가 바뀌면 지각 웹훅이 갈 곳을 잃는다").isPresent();
	}

	@Test
	@DisplayName("S5 — 재구독하면 옛 계약이 남은 채 새 계약이 따로 쌓인다")
	void 재구독은_계약을_따로_쌓는다() {
		start(USER, ORDER);
		expire(ORDER);
		OrderId secondOrder = new OrderId("sub-order-latest");
		start(USER, secondOrder);

		assertThat(subscriptionRepository.findByOrderId(ORDER))
			.get().extracting(Subscription::getStatus).isEqualTo(EXPIRED);
		assertThat(subscriptionRepository.findByOrderId(secondOrder))
			.get().extracting(Subscription::getStatus).isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S5 경계 — 다른 사용자의 구독은 서로 간섭하지 않는다")
	void 사용자별_분리() {
		start(USER, ORDER);
		start(new UserId(5002L), new OrderId("sub-order-other-user"));

		assertThat(subscriptionRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("S7 — 더 나중 웹훅만 반영된다. 과거·동일 시각 웹훅은 무시이고 오류가 아니다")
	void 웹훅_순서() {
		start(USER, ORDER);
		LocalDateTime first = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime firstExpiry = LocalDateTime.of(2026, 10, 1, 0, 0);

		receive(statusChanged(ORDER.raw(), first, null, snapshot(IN_GRACE_PERIOD, firstExpiry, true)));

		Subscription applied = theOnlyRow();
		assertThat(applied.getStatus()).isEqualTo(IN_GRACE_PERIOD);
		assertThat(applied.getExpiresAt()).isEqualTo(firstExpiry);
		assertThat(applied.getLastWebhookOccurredAt()).isEqualTo(first);
		assertThat(applied.getUpdatedAt())
			.as("더티 체킹이라 Auditing 이 그대로 탄다 — 반영된 행은 수정 시각이 찍혀야 한다")
			.isNotNull();

		// 과거 웹훅 — 무시
		assertThatCode(() -> receive(statusChanged(ORDER.raw(), first.minusDays(1), null,
			snapshot(EXPIRED, LocalDateTime.of(2025, 1, 1, 0, 0), false))))
			.as("과거 웹훅은 오류가 아니라 무동작이다").doesNotThrowAnyException();
		assertUnchangedSince(first, IN_GRACE_PERIOD, firstExpiry);

		// 동일 시각 재전송 — 무시
		receive(statusChanged(ORDER.raw(), first, null, snapshot(PAUSED, LocalDateTime.of(2025, 1, 1, 0, 0), false)));
		assertUnchangedSince(first, IN_GRACE_PERIOD, firstExpiry);

		// 더 나중 웹훅 — 반영
		LocalDateTime later = first.plusSeconds(1);
		LocalDateTime laterExpiry = LocalDateTime.of(2026, 11, 1, 0, 0);
		receive(statusChanged(ORDER.raw(), later, null, snapshot(ACTIVE, laterExpiry, false)));

		Subscription latest = theOnlyRow();
		assertThat(latest.getStatus()).isEqualTo(ACTIVE);
		assertThat(latest.getExpiresAt()).isEqualTo(laterExpiry);
		assertThat(latest.isAutoRenew()).isFalse();
		assertThat(latest.getLastWebhookOccurredAt()).isEqualTo(later);
	}

	@Test
	@DisplayName("S9 — expiresAt 이 null 인 웹훅은 기존 만료를 덮지 않고 상태만 반영한다")
	void 만료가_비어_오면_기존_값_유지() {
		start(USER, ORDER);
		LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 0, 0);

		receive(statusChanged(ORDER.raw(), occurredAt, null, snapshot(IN_GRACE_PERIOD, null, true)));

		Subscription subscription = theOnlyRow();
		assertThat(subscription.getExpiresAt())
			.as("🔴 만료를 null 로 덮으면 NOT NULL 위반이거나 만료 판정이 죽는다")
			.isEqualTo(ESTIMATED_EXPIRES_AT);
		assertThat(subscription.getStatus()).isEqualTo(IN_GRACE_PERIOD);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("S9 — autoRenew 가 null 인 웹훅도 기존 값을 유지한다")
	void 자동갱신이_비어_오면_기존_값_유지() {
		start(USER, ORDER);
		LocalDateTime first = LocalDateTime.of(2026, 9, 1, 0, 0);
		receive(statusChanged(ORDER.raw(), first, null, snapshot(ACTIVE, ESTIMATED_EXPIRES_AT, false)));

		receive(statusChanged(ORDER.raw(), first.plusDays(1), null, snapshot(ACTIVE, ESTIMATED_EXPIRES_AT, null)));

		assertThat(theOnlyRow().isAutoRenew())
			.as("비어 온 값을 false 로 읽으면 자동 갱신 상태가 조용히 뒤집힌다").isFalse();
	}

	@Test
	@DisplayName("S9 — occurredAt 이 null 인 웹훅은 반영되지만 순서 기준값은 그대로다")
	void 발생시각이_비어_오면_기준값_유지() {
		start(USER, ORDER);
		LocalDateTime first = LocalDateTime.of(2026, 9, 1, 0, 0);
		receive(statusChanged(ORDER.raw(), first, null, snapshot(ACTIVE, ESTIMATED_EXPIRES_AT, true)));

		receive(statusChanged(ORDER.raw(), null, null, snapshot(PAUSED, ESTIMATED_EXPIRES_AT, true)));

		Subscription subscription = theOnlyRow();
		assertThat(subscription.getStatus())
			.as("순서를 모른다고 버리면 상태 변화를 아는 유일한 경로가 새어나간다").isEqualTo(PAUSED);
		assertThat(subscription.getLastWebhookOccurredAt())
			.as("기준값을 null 로 덮으면 이후 모든 과거 웹훅이 다시 통과한다").isEqualTo(first);
	}

	@Test
	@DisplayName("S10 — previous 가 우리 상태와 다르면 유실을 남기되 반영은 그대로 진행한다")
	void 유실_감지() {
		start(USER, ORDER);   // 우리 상태 = ACTIVE
		LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 0, 0);
		LocalDateTime newExpiry = LocalDateTime.of(2026, 12, 1, 0, 0);

		// 토스는 직전 상태를 ON_HOLD 라고 알려준다 = ACTIVE → ON_HOLD 웹훅을 놓쳤다
		receive(statusChanged(ORDER.raw(), occurredAt,
			snapshot(SubscriptionStatus.ON_HOLD, null, true), snapshot(ACTIVE, newExpiry, true)));

		assertThat(warnings())
			.as("🔴 유실을 감지만 하고 남기지 않으면 매월 반복돼도 아무도 모른다")
			.anyMatch(message -> message.contains("직전 상태 불일치"));
		Subscription subscription = theOnlyRow();
		assertThat(subscription.getStatus()).as("감지했다고 반영을 멈추지 않는다").isEqualTo(ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(newExpiry);
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("S10 — previous 가 생략된 페이로드는 정상 처리되고 유실로 읽히지 않는다")
	void previous_생략() {
		start(USER, ORDER);
		LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 0, 0);

		receive(statusChanged(ORDER.raw(), occurredAt, null, snapshot(ACTIVE, null, true)));

		assertThat(warnings()).noneMatch(message -> message.contains("직전 상태 불일치"));
		assertThat(theOnlyRow().getLastWebhookOccurredAt()).isEqualTo(occurredAt);
	}

	@Test
	@DisplayName("S10 — previous 가 우리 상태와 같으면 유실 경보를 남기지 않는다")
	void previous_일치() {
		start(USER, ORDER);

		receive(statusChanged(ORDER.raw(), LocalDateTime.of(2026, 9, 1, 0, 0),
			snapshot(ACTIVE, null, true), snapshot(PAUSED, null, true)));

		assertThat(warnings()).noneMatch(message -> message.contains("직전 상태 불일치"));
		assertThat(theOnlyRow().getStatus()).isEqualTo(PAUSED);
	}

	@Test
	@DisplayName("S11 — 모르는 주문의 웹훅은 구독을 만들지 않고 조용히 무시된다")
	void 모르는_주문의_웹훅() {
		assertThatCode(() -> receive(statusChanged("unknown-order-id", LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(ACTIVE, LocalDateTime.of(2026, 10, 1, 0, 0), true))))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("🔴 위조 웹훅으로 결제 없는 구독이 생기면 방어의 마지막 줄이 뚫린다").isZero();
	}

	@Test
	@DisplayName("S11 — 다른 주문의 웹훅은 내 구독을 건드리지 않는다")
	void 다른_주문의_웹훅은_내_구독을_건드리지_않는다() {
		start(USER, ORDER);

		receive(statusChanged("some-other-order", LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(EXPIRED, null, false)));

		assertThat(theOnlyRow().getStatus()).isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S12 — 시크릿이 다르면 AUTH_002 로 거부하고 아무것도 반영하지 않는다")
	void 시크릿_불일치() {
		start(USER, ORDER);
		WebhookEvent event = statusChanged(ORDER.raw(), LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(EXPIRED, null, false));

		assertThatThrownBy(() -> subscriptionStatus.handleWebhook("wrong-secret", event))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_002);

		assertThat(theOnlyRow().getStatus()).as("거부된 웹훅이 반영되면 검증이 무의미하다").isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S12 — 시크릿이 없으면 AUTH_002 로 거부한다 — 형식 오류가 아니라 인증 실패다")
	void 시크릿_누락() {
		WebhookEvent event = statusChanged(ORDER.raw(), LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(ACTIVE, null, true));

		assertThatThrownBy(() -> subscriptionStatus.handleWebhook(null, event))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_002);
	}

	@Test
	@DisplayName("S12 — 모르는 상태 어휘의 웹훅은 COMMON_001 이고 아무것도 반영되지 않는다")
	void 모르는_상태_어휘() {
		start(USER, ORDER);
		WebhookEvent unknownVocabulary = new WebhookEvent("subscription.status_changed", "1.0",
			LocalDateTime.of(2026, 9, 1, 0, 0), ORDER.raw(), "test.subscription", "SOMETHING_NEW",
			new WebhookEvent.SubscriptionChange(null,
				new WebhookEvent.Snapshot("TOSS_INVENTED_THIS", true, null, true)));

		assertThatThrownBy(() -> subscriptionStatus.handleWebhook(AUTH_HEADER, unknownVocabulary))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);

		assertThat(theOnlyRow().getStatus())
			.as("어휘 판정이 갱신보다 먼저다 — 여기가 바뀌면 판정 순서가 뒤집힌 것이다").isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S12 — 스냅샷이 통째로 빠진 페이로드는 500 이 아니라 COMMON_001 이다")
	void 망가진_페이로드() {
		start(USER, ORDER);
		WebhookEvent broken = new WebhookEvent("subscription.status_changed", "1.0",
			LocalDateTime.of(2026, 9, 1, 0, 0), ORDER.raw(), "test.subscription", "RENEWED", null);

		assertThatThrownBy(() -> subscriptionStatus.handleWebhook(AUTH_HEADER, broken))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);

		assertThat(theOnlyRow().getStatus()).isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S12 — orderId 없는 상태 변경 웹훅은 COMMON_001 이다")
	void 주문_식별자가_없는_상태_변경() {
		WebhookEvent noOrderId = new WebhookEvent("subscription.status_changed", "1.0",
			LocalDateTime.of(2026, 9, 1, 0, 0), null, "test.subscription", "RENEWED",
			new WebhookEvent.SubscriptionChange(null,
				new WebhookEvent.Snapshot(ACTIVE.name(), true, null, true)));

		assertThatThrownBy(() -> subscriptionStatus.handleWebhook(AUTH_HEADER, noOrderId))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);
	}

	@Test
	@DisplayName("S12 — 등록 검증 이벤트(orderId 없음)는 본문 처리 없이 수신 성공이다")
	void 등록_검증_이벤트() {
		start(USER, ORDER);
		WebhookEvent registration = new WebhookEvent(WebhookEvent.TYPE_REGISTRATION_VERIFICATION,
			"1.0", null, null, null, null, null);

		assertThatCode(() -> subscriptionStatus.handleWebhook(AUTH_HEADER, registration))
			.as("orderId 가 없는 페이로드를 상태 변경으로 읽으면 여기서 터진다").doesNotThrowAnyException();

		assertThat(theOnlyRow().getStatus()).isEqualTo(ACTIVE);
	}

	@Test
	@DisplayName("S16 — 재확인은 상태·만료를 보정해도 웹훅 순서 기준값을 건드리지 않는다")
	void 재확인은_웹훅_기준값을_건드리지_않는다() {
		LocalDateTime webhookAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		staleSubscription(webhookAt);

		subscriptionStatus.recheck(USER,
			new SubscriptionSnapshot(ORDER.raw(), ACTIVE.name(), LocalDateTime.of(2026, 9, 30, 0, 0), true));

		Subscription corrected = theOnlyRow();
		assertThat(corrected.getExpiresAt())
			.as("보정 자체는 일어나야 한다 — 상태 미확인을 푸는 유일한 수단이다")
			.isEqualTo(LocalDateTime.of(2026, 9, 30, 0, 0));
		assertThat(corrected.getLastWebhookOccurredAt())
			.as("🔴 여기가 갱신되면 뒤늦게 온 웹훅이 과거로 취급돼 버려진다")
			.isEqualTo(webhookAt);

		// 규칙의 목적 확인 — 보정 직후에 도착한(기준값보다 조금 나중인) 웹훅이 여전히 이긴다
		LocalDateTime lateWebhook = webhookAt.plusSeconds(1);
		receive(statusChanged(ORDER.raw(), lateWebhook, null,
			snapshot(EXPIRED, LocalDateTime.of(2026, 7, 15, 0, 0), false)));

		Subscription authoritative = theOnlyRow();
		assertThat(authoritative.getStatus())
			.as("🔴 정본(웹훅)이 임시 보정을 이겨야 한다").isEqualTo(EXPIRED);
		assertThat(authoritative.getLastWebhookOccurredAt()).isEqualTo(lateWebhook);
	}

	@Test
	@DisplayName("S16 — 미확인이 아닌 구독은 재확인해도 아무것도 바뀌지 않는다")
	void 미확인이_아니면_무동작() {
		start(USER, ORDER);   // 만료가 FIXED_NOW + 31일 = 미확인 아님

		subscriptionStatus.recheck(USER,
			new SubscriptionSnapshot(ORDER.raw(), EXPIRED.name(), LocalDateTime.of(2020, 1, 1, 0, 0), false));

		Subscription subscription = theOnlyRow();
		assertThat(subscription.getStatus())
			.as("클라이언트 값이 멀쩡한 정본을 덮으면 검증되지 않은 값이 이긴다").isEqualTo(ACTIVE);
		assertThat(subscription.getExpiresAt()).isEqualTo(ESTIMATED_EXPIRES_AT);
	}

	@Test
	@DisplayName("S16 — 모르는 주문으로 재확인하면 오류 없이 무동작이다")
	void 모르는_주문이면_무동작() {
		assertThatCode(() -> subscriptionStatus.recheck(new UserId(5999L),
			new SubscriptionSnapshot("no-such-order", ACTIVE.name(), LocalDateTime.of(2026, 12, 1, 0, 0), true)))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("재확인은 구독을 만들지 않는다 — 생성 경로는 검증된 주문 하나뿐이다").isZero();
	}

	@Test
	@DisplayName("S16 — 남의 주문을 지목한 재확인은 보정하지 않는다")
	void 남의_주문은_보정하지_않는다() {
		LocalDateTime webhookAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		LocalDateTime knownExpiry = staleSubscription(webhookAt);

		assertThatCode(() -> subscriptionStatus.recheck(new UserId(5999L),
			new SubscriptionSnapshot(ORDER.raw(), PAUSED.name(), LocalDateTime.of(2026, 12, 1, 0, 0), false)))
			.as("실패로 답하면 주문 존재 여부가 새어 나간다").doesNotThrowAnyException();

		assertUnchangedSince(webhookAt, ACTIVE, knownExpiry);
	}

	@Test
	@DisplayName("S16 — 재확인은 지목한 주문의 계약만 보정한다")
	void 지목한_계약만_보정한다() {
		LocalDateTime webhookAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		staleSubscription(webhookAt);
		OrderId secondOrder = new OrderId("sub-order-latest");
		start(USER, secondOrder);

		subscriptionStatus.recheck(USER,
			new SubscriptionSnapshot(ORDER.raw(), PAUSED.name(), LocalDateTime.of(2026, 12, 1, 0, 0), false));

		assertThat(subscriptionRepository.findByOrderId(ORDER).orElseThrow().getStatus())
			.as("지목한 계약이 보정되지 않으면 재확인이 미확인을 풀지 못한다").isEqualTo(PAUSED);
		assertThat(subscriptionRepository.findByOrderId(secondOrder).orElseThrow())
			.as("지목하지 않은 계약을 건드리면 멀쩡한 정본이 클라이언트 값으로 덮인다")
			.extracting(Subscription::getStatus, Subscription::getExpiresAt)
			.containsExactly(ACTIVE, ESTIMATED_EXPIRES_AT);
	}

	@Test
	@DisplayName("S16 — 모르는 상태 값으로 재확인하면 COMMON_001 이다")
	void 모르는_상태_값() {
		staleSubscription(LocalDateTime.of(2026, 7, 1, 0, 0));

		assertThatThrownBy(() -> subscriptionStatus.recheck(USER,
			new SubscriptionSnapshot(ORDER.raw(), "NOT_A_STATUS", LocalDateTime.of(2026, 12, 1, 0, 0), true)))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);
	}

	@Test
	@DisplayName("S16 — 재확인의 expiresAt 이 null 이면 기존 만료를 유지한다")
	void 재확인_만료가_비면_기존_값_유지() {
		LocalDateTime webhookAt = LocalDateTime.of(2026, 7, 1, 0, 0);
		LocalDateTime knownExpiry = staleSubscription(webhookAt);

		subscriptionStatus.recheck(USER, new SubscriptionSnapshot(ORDER.raw(), PAUSED.name(), null, false));

		Subscription subscription = theOnlyRow();
		assertThat(subscription.getExpiresAt())
			.as("비어 온 값으로 만료를 지우면 만료 판정이 통째로 죽는다").isEqualTo(knownExpiry);
		assertThat(subscription.getStatus()).isEqualTo(PAUSED);
		assertThat(subscription.isAutoRenew()).isFalse();
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(webhookAt);
	}

	private void start(UserId userId, OrderId orderId) {
		listenerTx.executeWithoutResult(status -> subscriptionGrant.start(userId, orderId));
	}

	private void receive(WebhookEvent event) {
		subscriptionStatus.handleWebhook(AUTH_HEADER, event);
	}

	private void expire(OrderId orderId) {
		receive(statusChanged(orderId.raw(), FIXED_NOW.minusDays(1), null,
			snapshot(EXPIRED, FIXED_NOW.minusDays(1), false)));
	}

	private LocalDateTime staleSubscription(LocalDateTime webhookAt) {
		start(USER, ORDER);
		LocalDateTime pastExpiry = FIXED_NOW.minusDays(10);
		receive(statusChanged(ORDER.raw(), webhookAt, null, snapshot(ACTIVE, pastExpiry, true)));
		return pastExpiry;
	}

	private void assertUnchangedSince(LocalDateTime lastWebhookAt, SubscriptionStatus status,
			LocalDateTime expiresAt) {
		Subscription subscription = theOnlyRow();
		assertThat(subscription.getLastWebhookOccurredAt()).isEqualTo(lastWebhookAt);
		assertThat(subscription.getStatus()).isEqualTo(status);
		assertThat(subscription.getExpiresAt()).isEqualTo(expiresAt);
	}

	private Subscription theOnlyRow() {
		List<Subscription> rows = subscriptionRepository.findAll();
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private List<String> warnings() {
		return logs.list.stream()
			.filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage)
			.toList();
	}

	private static Logger serviceLogger() {
		return (Logger) LoggerFactory.getLogger("kang20.ytcreator.subscription.internal.service.SubscriptionService");
	}
}
