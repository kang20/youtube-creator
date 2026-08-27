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

/**
 * S1·S5·S6·S7·S9·S10·S11·S12·S16 — 구독 개시 · 웹훅 반영 · 재확인의 서비스 흐름
 * (payment.md [구독 애그리거트] 규칙 전체 · "[구독의 상태 변환 감지]" · "[구독 갱신의 유실 상태]").
 *
 * <p><b>실제 DB(H2)를 쓴다</b> — 이 도메인의 불변식 심판이 UNIQUE 제약과 행 잠금이라, 저장소를
 * 목으로 바꾸면 검증 대상이 통째로 사라진다(testing.md 작성 원칙 2).
 *
 * <p>⚠️ 클래스에 {@code @Transactional} 을 붙이지 않는다 — 붙이면 {@code REQUIRES_NEW} 삽입과
 * 쓰기 빈의 트랜잭션이 테스트 트랜잭션에 흡수돼 설계 전제를 검증하지 못한다(CreditServiceTest 선례).
 * 대신 운영 호출자(동기 리스너 — 발행자의 원장 트랜잭션 안)를 {@link TransactionTemplate} 로
 * 재현한다.
 *
 * <p>시간은 {@code Clock.fixed} 로 고정한다 — 추정 만료(+31일)를 값으로 단언할 수 있어야 한다
 * (testing.md "시간·랜덤은 주입"). {@code updated_at} 은 JPA Auditing 이 시스템 시계로 찍으므로
 * 값 단언 대상이 아니다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest
@Import({JpaAuditingConfig.class, SubscriptionServiceTest.TestClockConfig.class})
@TestPropertySource(properties = "ytcreator.subscription.webhook.secret=" + SubscriptionServiceTest.SECRET)
class SubscriptionServiceTest {

	/** 콘솔에 등록하는 Basic Auth 값(테스트 전용). 운영은 {@code TOSS_WEBHOOK_SECRET} 환경변수다. */
	static final String SECRET = "test-webhook-shared-secret";

	/** 토스가 실제로 보내는 헤더 모양 — 설정값에 Basic 스킴이 붙는다. */
	private static final String AUTH_HEADER = "Basic " + SECRET;

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 14, 12, 0, 0);

	/** 개시 시 추정 만료 = 결제 반영 시각 + 31일 (SubscriptionService.ESTIMATED_PERIOD_DAYS). */
	private static final LocalDateTime ESTIMATED_EXPIRES_AT = FIXED_NOW.plusDays(31);

	/** {@code TimeConfig} 직접 import 는 Modulith 빈 선별기를 죽인다(AuthServiceTest javadoc 실측). */
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

	// ── S1 — 개시 ───────────────────────────────────────────────────────

	/**
	 * S1 — 구독은 <b>활성 · 추정 만료 · 웹훅 미수신</b>으로 태어난다
	 * (payment.md 구독 애그리거트 {@code static start()}).
	 *
	 * <p>{@code lastWebhookOccurredAt} 이 {@code null} 인 것이 "아직 정본을 못 받았다"의 표현이고,
	 * 이 값이 {@code null} 이면 첫 웹훅은 발생 시각과 무관하게 무조건 반영된다.
	 */
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

	// ── S6 — 같은 주문 재지급 = 멱등 성공 ───────────────────────────────

	/**
	 * S6 — 같은 주문으로 다시 개시해도 <b>구독은 하나이고 오류가 아니다</b>
	 * ({@code UNIQUE(order_id)} 위반 = 멱등의 근거. {@code SubscriptionGrantPort} javadoc).
	 *
	 * <p>이벤트 재전달·재발행이 정상 경로이므로 여기서 거부하면 리스너가 영원히 재시도한다.
	 * <b>다른 주문</b>이면 새 계약이 열린다(S5) — 같은 주문일 때만 접는다.
	 */
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

	// ── S5 — 재구독은 새 계약으로 열린다 ────────────────────────────────

	/**
	 * S5 — 🔴 <b>같은 사용자라도 다른 주문이면 새 계약이 열린다</b>(2026-08-16 결정).
	 *
	 * <p>예전에는 {@code UNIQUE(user_id)} 로 막고 {@code SUB_001} 로 거부했는데, 그 제약은
	 * {@code status} 를 모르기 때문에 실제로는 <b>"평생 한 번만 구독 가능"</b>을 강제하고 있었다.
	 * 만료·해지 뒤 재구독하면 종료된 행이 자리를 점유해 <b>영구히 거부</b>됐고, 리스너가 그 거부를
	 * 삼키는 바람에 <b>돈은 받고 구독은 없는</b> 상태가 조용히 남았다.
	 *
	 * <p>이 테스트가 그 회귀를 막는다 — 행이 두 개로 늘어야 하고, 옛 계약의 {@code order_id} 가
	 * 그대로여야 지각 웹훅이 갈 곳을 잃지 않는다.
	 */
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

	/**
	 * S5 — 재구독하면 계약이 <b>따로 쌓인다.</b> 옛 계약을 덮어 쓰면 그 계약으로 오는 지각 웹훅이
	 * 갈 곳을 잃고, 계약마다 다른 주문 식별자로 독립 조회되어야 재확인이 대상을 지목할 수 있다.
	 */
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

	/** S5 — 사용자가 다르면 서로 간섭하지 않는다. */
	@Test
	@DisplayName("S5 경계 — 다른 사용자의 구독은 서로 간섭하지 않는다")
	void 사용자별_분리() {
		start(USER, ORDER);
		start(new UserId(5002L), new OrderId("sub-order-other-user"));

		assertThat(subscriptionRepository.count()).isEqualTo(2);
	}

	// ── S7 — 웹훅 순서 ──────────────────────────────────────────────────

	/**
	 * S7 — <b>더 나중 {@code occurredAt} 웹훅만 반영된다.</b> 과거·동일 시각은 무동작이며
	 * <b>오류가 아니다</b>(payment.md "이미 반영한 것보다 과거의 웹훅은 반영하지 않는다").
	 *
	 * <p>동일 시각까지 무시하는 이유: 웹훅에 이벤트 식별자가 없어 같은 시각의 재전송을 새 사건과
	 * 구분할 수 없다 — 조건이 {@code <} 라 같은 값은 통과하지 못한다.
	 */
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

	// ── S9 — 비어 오는 값은 기존 값을 유지한다 ──────────────────────────

	/**
	 * S9 — 🔴 <b>{@code expiresAt} 이 {@code null} 로 오면 기존 만료를 유지한다.</b>
	 * {@code CREATED} 예시가 실제로 {@code null} 이고(참고자료 ②), <b>정본을 무로 덮으면 만료 판정이
	 * 통째로 죽는다</b>(payment.md "비어 있으면 기존 값을 유지한다").
	 */
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

	/** S9 자매 — {@code autoRenew} 도 비어 올 수 있다. 같은 규칙(기존 값 유지)이 적용된다. */
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

	/**
	 * S9 자매 — {@code occurredAt} 이 비어 오면 <b>반영하되 순서 기준값은 갱신하지 않는다</b>.
	 * 순서를 판정할 근거가 없는 것이지 "과거"라는 뜻이 아니다(payment.md "발생 시각은 비워 보낼 수 있다").
	 */
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

	// ── S10 — 유실 감지 ────────────────────────────────────────────────

	/**
	 * S10 — 🔴 <b>{@code previous} 가 우리 상태와 다르면 그 사이의 웹훅을 놓친 것이다.</b>
	 * <b>감지해서 남기되 반영은 그대로 진행한다</b>(payment.md 구독 규칙) — 놓친 사건을 되살릴
	 * 수단이 없으므로, 반영까지 멈추면 아는 것도 못 쓰게 된다.
	 */
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

	/**
	 * S10 — {@code previous} 는 <b>생략될 수 있다</b>({@code CREATED} 처럼 이전 상태가 없을 때).
	 * 없다고 유실로 읽지 않는다 — 그러면 정상 생성 웹훅마다 거짓 경보가 뜬다.
	 */
	@Test
	@DisplayName("S10 — previous 가 생략된 페이로드는 정상 처리되고 유실로 읽히지 않는다")
	void previous_생략() {
		start(USER, ORDER);
		LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 0, 0);

		receive(statusChanged(ORDER.raw(), occurredAt, null, snapshot(ACTIVE, null, true)));

		assertThat(warnings()).noneMatch(message -> message.contains("직전 상태 불일치"));
		assertThat(theOnlyRow().getLastWebhookOccurredAt()).isEqualTo(occurredAt);
	}

	/** S10 — {@code previous} 가 우리 상태와 같으면 유실이 아니다(연속된 웹훅의 정상 경로). */
	@Test
	@DisplayName("S10 — previous 가 우리 상태와 같으면 유실 경보를 남기지 않는다")
	void previous_일치() {
		start(USER, ORDER);

		receive(statusChanged(ORDER.raw(), LocalDateTime.of(2026, 9, 1, 0, 0),
			snapshot(ACTIVE, null, true), snapshot(PAUSED, null, true)));

		assertThat(warnings()).noneMatch(message -> message.contains("직전 상태 불일치"));
		assertThat(theOnlyRow().getStatus()).isEqualTo(PAUSED);
	}

	// ── S11 — 웹훅으로 구독을 만들지 않는다 ─────────────────────────────

	/**
	 * S11 — 🔴 <b>모르는 주문의 웹훅은 무시한다.</b> 구독은 검증된 주문을 거쳐서만 생긴다
	 * (payment.md "웹훅으로 구독을 새로 만들지 않는다"). 이 규칙이 위조 웹훅의 최대 피해를
	 * "없는 구독 생성"에서 "이미 결제한 사람의 상태 흔들기"로 줄인다.
	 */
	@Test
	@DisplayName("S11 — 모르는 주문의 웹훅은 구독을 만들지 않고 조용히 무시된다")
	void 모르는_주문의_웹훅() {
		assertThatCode(() -> receive(statusChanged("unknown-order-id", LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(ACTIVE, LocalDateTime.of(2026, 10, 1, 0, 0), true))))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("🔴 위조 웹훅으로 결제 없는 구독이 생기면 방어의 마지막 줄이 뚫린다").isZero();
	}

	/** S11 — 남의 주문 웹훅이 내 구독을 건드리지 않는다(조회 키는 {@code order_id} 하나다). */
	@Test
	@DisplayName("S11 — 다른 주문의 웹훅은 내 구독을 건드리지 않는다")
	void 다른_주문의_웹훅은_내_구독을_건드리지_않는다() {
		start(USER, ORDER);

		receive(statusChanged("some-other-order", LocalDateTime.of(2026, 9, 1, 0, 0),
			null, snapshot(EXPIRED, null, false)));

		assertThat(theOnlyRow().getStatus()).isEqualTo(ACTIVE);
	}

	// ── S12 — 진위 검증과 수신 성공 ────────────────────────────────────

	/** S12 — 시크릿이 다르면 거부한다. 위조 웹훅을 처리하지 않는 1차 방어다. */
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

	/** S12 — 헤더가 아예 없으면(=null) 400 이 아니라 <b>인증 실패</b>다. */
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

	/**
	 * S12 — <b>모르는 상태 어휘는 입력 오류다</b>(2026-08-18 결정 — 전에는 삼켜서 204 로 답했다).
	 *
	 * <p>재전송 정책이 없어 삼키든 던지든 그 사건은 다시 오지 않는다 — 유실량이 같다면 실패가
	 * 응답에 드러나는 쪽이 낫다. 🔴 <b>반영은 하나도 일어나지 않아야 한다</b>: 어휘 판정이
	 * 갱신보다 먼저라 기존 상태가 그대로여야 한다.
	 */
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

	/**
	 * S12 — 페이로드가 성립하지 않으면(스냅샷 없음) <b>NPE 가 아니라 입력 오류</b>다.
	 * 방어가 상태 어휘에만 걸려 있으면 여기서 500 이 나간다.
	 */
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

	/** S12 — 상태 변경 이벤트에 주문 식별자가 없으면 구독을 찾을 근거가 없다 — 입력 오류다. */
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

	/**
	 * S12 — 콜백 URL 등록 검증 이벤트는 <b>본문 처리 없이</b> 수신 성공만 답한다.
	 * <b>이걸 정상 수신해야 URL 이 활성화된다</b>(참고자료 ②) — 여기서 터지면 웹훅 경로가 아예 안 열린다.
	 */
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

	// ── S16 — 재확인(recheck) ──────────────────────────────────────────

	/**
	 * S16 — 🔴 <b>이 도메인의 급소.</b> 재확인은 상태·만료를 보정하지만
	 * <b>{@code lastWebhookOccurredAt} 은 건드리지 않는다.</b>
	 *
	 * <p>건드리면 뒤늦게 도착한 웹훅이 "과거 이벤트"로 버려지고 <b>클라이언트가 보낸 값이 영구히
	 * 정본</b>이 된다(payment.md 구독 규칙). 그래서 기준값을 그대로 두는 것만이 아니라,
	 * <b>보정 직후에 온 웹훅이 여전히 반영되는지</b>까지 확인한다 — 그게 규칙의 목적이다.
	 */
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

	/** S16 — 상태 미확인이 아니면 보정하지 않는다. 정본이 멀쩡한 구간을 검증되지 않은 값이 이기면 안 된다. */
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

	/**
	 * S16 — 모르는 주문이면 <b>무동작 204</b>다(blockers.md B1 임시 결정 A).
	 * 보정할 대상이 없는 것은 오류가 아니다 — 새 ErrorCode 를 발명하지 않는다.
	 */
	@Test
	@DisplayName("S16 — 모르는 주문으로 재확인하면 오류 없이 무동작이다")
	void 모르는_주문이면_무동작() {
		assertThatCode(() -> subscriptionStatus.recheck(new UserId(5999L),
			new SubscriptionSnapshot("no-such-order", ACTIVE.name(), LocalDateTime.of(2026, 12, 1, 0, 0), true)))
			.doesNotThrowAnyException();

		assertThat(subscriptionRepository.count())
			.as("재확인은 구독을 만들지 않는다 — 생성 경로는 검증된 주문 하나뿐이다").isZero();
	}

	/**
	 * S16 — 🔴 <b>지목은 클라이언트가 하고 소유는 토큰이 정한다.</b> 남의 {@code orderId} 로 보정이
	 * 되면 {@code orderId} 가 곧 권한이 되고, 그 값은 사실상 bearer 토큰이다(iap-spec.md ⑫).
	 *
	 * <p>모르는 주문과 <b>같은 무동작</b>이어야 한다 — 갈라 답하면 남의 주문이 존재하는지 알려주는
	 * 열거 표면이 된다.
	 */
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

	/**
	 * S16 — 🔴 <b>보정 대상은 지목한 계약 하나다.</b> 재구독으로 계약이 쌓였을 때 엉뚱한 계약을
	 * 집으면 지나간 계약이 되살아나거나, 클라이언트가 읽은 스냅샷과 다른 계약이 덮인다.
	 */
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

	/** S16 — 모르는 상태 어휘는 <b>입력 오류</b>다. 웹훅(무시)과 처리가 다르다 — 여기는 우리 API 계약이다. */
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

	/** S16 — SDK 가 만료를 비워 줄 수 있다. 웹훅과 <b>같은 규칙</b>으로 기존 값을 유지한다. */
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

	// ── helpers ────────────────────────────────────────────────────────

	/** 운영 호출자(동기 리스너)의 트랜잭션 경계를 재현한다 — 발행자(payment)의 원장 트랜잭션 안이다. */
	private void start(UserId userId, OrderId orderId) {
		listenerTx.executeWithoutResult(status -> subscriptionGrant.start(userId, orderId));
	}

	private void receive(WebhookEvent event) {
		subscriptionStatus.handleWebhook(AUTH_HEADER, event);
	}

	/** 구독을 만료시킨다 — 재구독 시나리오의 전제. 행은 지워지지 않고 {@code status} 만 바뀐다. */
	private void expire(OrderId orderId) {
		receive(statusChanged(orderId.raw(), FIXED_NOW.minusDays(1), null,
			snapshot(EXPIRED, FIXED_NOW.minusDays(1), false)));
	}

	/**
	 * 상태 미확인 구독을 만든다 — 웹훅으로 만료를 <b>과거</b>로 옮겨 완충(3일)까지 지나게 한다.
	 *
	 * <p>웹훅을 한 번 받은 뒤의 미확인이라는 점이 중요하다 — 추정 만료 상태로만 재확인을 검증하면
	 * "웹훅 수신 여부로 분기해도 통과하는" 테스트가 된다.
	 *
	 * @return 웹훅이 정본으로 박아 넣은 만료 시각
	 */
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
