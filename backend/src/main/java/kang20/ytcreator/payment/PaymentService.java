package kang20.ytcreator.payment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductCatalog;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;
import kang20.ytcreator.payment.dto.TicketSource;
import kang20.ytcreator.payment.dto.UsageTicketView;
import kang20.ytcreator.payment.dto.WebhookEvent;
import kang20.ytcreator.payment.internal.entity.CreditBalance;
import kang20.ytcreator.payment.internal.repository.CreditBalanceRepository;
import kang20.ytcreator.payment.internal.writer.GrantWriter;
import kang20.ytcreator.payment.internal.support.OrderIdMask;
import kang20.ytcreator.payment.internal.entity.PaymentOrder;
import kang20.ytcreator.payment.internal.repository.PaymentOrderRepository;
import kang20.ytcreator.payment.internal.support.ProductCatalogProperties;
import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.writer.SubscriptionApplyWriter;
import kang20.ytcreator.payment.internal.support.SubscriptionGate;
import kang20.ytcreator.payment.internal.repository.SubscriptionRepository;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;
import kang20.ytcreator.payment.internal.entity.TicketStatus;
import kang20.ytcreator.payment.internal.client.TossOrderClient;
import kang20.ytcreator.payment.internal.client.TossOrderStatus;
import kang20.ytcreator.payment.internal.entity.UsageTicket;
import kang20.ytcreator.payment.internal.repository.UsageTicketRepository;
import kang20.ytcreator.payment.internal.support.WebhookAuthenticator;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·이용권 모듈 공개 API — <b>모듈 밖에서 부를 수 있는 유일한 진입</b>이다(payment-design.md §4).
 *
 * <p>사용자 단위 메서드는 전부 {@link UserId} 를 받는다 — <b>익명키를 받지 않는다.</b>
 * 익명키→{@code UserId} 해석은 호출자(컨트롤러·bootstrap) 몫이다(§2-1 쟁점 1·§5-3).
 *
 * <p>U14: 응답·예외 메시지·애플리케이션 로그 어디에도 {@code orderId} 원문을 싣지 않는다(§9).
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	/** 구독 갱신 주기 표기(§5-1) — 매월(30일 고정, 달력 월 아님). */
	private static final String RENEWAL_CYCLE_MONTHLY = "MONTHLY";

	/**
	 * 저장은 KST 해석 {@code LocalDateTime}, 응답 직렬화 시점에만 +09:00 을 붙인다(§12-4 권고안).
	 * ⚠️ {@code config.TimeConfig} 를 참조하지 않는다 — {@code config} 는 allowedDependencies 밖이다.
	 */
	private static final ZoneOffset KST_OFFSET = ZoneOffset.ofHours(9);

	private final PaymentOrderRepository orderRepository;
	private final CreditBalanceRepository creditRepository;
	private final SubscriptionRepository subscriptionRepository;
	private final UsageTicketRepository usageTicketRepository;
	private final GrantWriter grantWriter;
	private final SubscriptionApplyWriter applyWriter;
	private final TossOrderClient tossOrderClient;
	private final WebhookAuthenticator authenticator;
	private final SubscriptionGate gate;
	private final ProductCatalogProperties catalog;
	private final Clock clock;

	public PaymentService(PaymentOrderRepository orderRepository, CreditBalanceRepository creditRepository,
			SubscriptionRepository subscriptionRepository, UsageTicketRepository usageTicketRepository,
			GrantWriter grantWriter, SubscriptionApplyWriter applyWriter, TossOrderClient tossOrderClient,
			WebhookAuthenticator authenticator, SubscriptionGate gate, ProductCatalogProperties catalog,
			Clock clock) {
		this.orderRepository = orderRepository;
		this.creditRepository = creditRepository;
		this.subscriptionRepository = subscriptionRepository;
		this.usageTicketRepository = usageTicketRepository;
		this.grantWriter = grantWriter;
		this.applyWriter = applyWriter;
		this.tossOrderClient = tossOrderClient;
		this.authenticator = authenticator;
		this.gate = gate;
		this.catalog = catalog;
		this.clock = clock;
	}

	/**
	 * U1(§5-1 · payment.md §5-2) — 설정에서 읽는다. DB 를 보지 않고 가격을 담지 않는다
	 * (SDK displayAmount 가 정본). sku 미설정 상품은 <b>키 전체가 null</b>(미노출).
	 */
	public ProductCatalog products() {
		String oneTimeSku = catalog.oneTimeSku();
		String subscriptionSku = catalog.subscriptionSku();
		return new ProductCatalog(
			oneTimeSku == null ? null
				: new ProductCatalog.OneTime(oneTimeSku, ProductType.CONSUMABLE.name()),
			subscriptionSku == null ? null
				: new ProductCatalog.Subscription(
					subscriptionSku, ProductType.SUBSCRIPTION.name(), RENEWAL_CYCLE_MONTHLY, null));
	}

	/**
	 * U2·U3·U4 지급(§5-2) — 이 도메인의 심장. 이 멱등 구조가 곧 U12(미결 주문 복원)의 구현이다.
	 *
	 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 마라 — 의도적으로 없다(§6-2).</b>
	 * 바깥 트랜잭션은 ⓐ 토스 왕복까지 감싸 커넥션 풀과 30초 예산을 동시에 무너뜨리고(함정 ④)
	 * ⓑ MySQL REPEATABLE READ 스냅샷에 갇혀 경쟁 패자의 재조회가 거짓말한다(함정 ③ —
	 * H2 에서는 재현되지 않고 운영에서만 터진다). 쓰기 원자성은 {@link GrantWriter}(REQUIRES_NEW)
	 * 안에 있다(§6-5).
	 */
	public GrantResult grant(UserId userId, String orderId) {
		// 1. 멱등·선점 선판정 — 이미 지급된 주문이면 토스를 부르지 않는다(복원 U12 가 이 경로를 자주 탄다)
		Optional<PaymentOrder> existing = orderRepository.findByOrderId(orderId);
		if (existing.isPresent()) {
			return replayOrReject(existing.get(), userId);
		}

		// 2. 토스 검증 — 트랜잭션 밖(§2-1 쟁점 2)
		TossOrderStatus toss = tossOrderClient.statusOf(orderId);
		if (!toss.available()) {
			throw new BusinessException(ErrorCode.PAY_006);
		}
		if (!toss.grantable()) {
			throw new BusinessException(toss.rejection());
		}

		// 3. 상품 판별 — 토스가 답한 sku 로 한다(클라 주장을 믿지 않는다). 카탈로그에 없으면 남의 상품(§5-2③)
		ProductType type = catalog.typeOf(toss.sku())
			.orElseThrow(() -> new BusinessException(ErrorCode.PAY_004));

		// 4. 지급 쓰기 — REQUIRES_NEW(§6-5). UNIQUE 위반만 경쟁으로 본다 — JPA(saveAndFlush) 경로는
		//    UNIQUE 위반을 DataIntegrityViolationException 으로 변환하므로(라운드 1 실측 —
		//    DuplicateKeyException 세분화는 JdbcTemplate 경로에만 있다) 원인 체인의 Hibernate
		//    kind 로 좁힌다. NOT NULL 위반·길이 초과는 경쟁 분기로 흘리지 않는다(§6-3 의 의도)
		LocalDateTime now = LocalDateTime.now(clock);
		try {
			grantWriter.grant(userId, orderId, toss.sku(), type, now);
		} catch (DataIntegrityViolationException firstLoss) {
			if (!isUniqueViolation(firstLoss)) {
				throw firstLoss;   // NOT NULL·길이 초과는 경쟁이 아니다 — §6-3 의 의도 유지
			}
			GrantResult settled = settleOrderCompetition(userId, orderId);
			if (settled != null) {
				return settled;   // C1 — order_id 경쟁. 멱등 200 또는 PAY_005(§6-5 매트릭스)
			}
			if (type == ProductType.SUBSCRIPTION) {
				// 주문 행이 없는 구독 중복 = UNIQUE(user_id) — 두 번째 활성 구독은 DB 가 거부한다(§3)
				throw new BusinessException(ErrorCode.PAY_003);
			}
			// C2 — 잔량 행 생성 경쟁. 행은 경쟁자가 만들었다 → 새 트랜잭션으로 정확히 1회 재시도(§6-4)
			try {
				grantWriter.grant(userId, orderId, toss.sku(), type, now);
			} catch (DataIntegrityViolationException secondLoss) {
				if (!isUniqueViolation(secondLoss)) {
					throw secondLoss;
				}
				GrantResult settledAgain = settleOrderCompetition(userId, orderId);
				if (settledAgain != null) {
					return settledAgain;
				}
				throw secondLoss;   // 판정 불가 — 루프를 돌지 않는다(§6-4). 안전망 500
			}
		}

		// 5. 응답은 커밋 이후에 만든다(payment.md §4-5-1ⓒ)
		return new GrantResult(true, type, entitlementOf(userId));
	}

	/**
	 * U5 이용권 조회(§5-3) — 읽기 전용. <b>토스도 auth 도 부르지 않는다.</b>
	 * 부트스트랩이 부분 실패를 허용하지 않으므로 여기서 외부를 부르면 토스 장애가 진입을 막는다.
	 * 이용권이 없어도 404 가 아니라 200 — "없음"은 정상 상태다.
	 */
	public EntitlementView entitlementOf(UserId userId) {
		int credits = creditRepository.findByUserId(userId).map(CreditBalance::getBalance).orElse(0);
		Subscription subscription = subscriptionRepository.findByUserId(userId).orElse(null);
		LocalDateTime now = LocalDateTime.now(clock);

		boolean stale = gate.stale(subscription, now);
		boolean accessible = gate.accessible(subscription, now) || credits > 0;
		return new EntitlementView(accessible, credits, stale, toSubscriptionView(subscription));
	}

	/**
	 * §4-7-1⑥ STALE 해소(§5-5). 🔴 {@code fromClient} 는 클라가 보낸 값이다 — 알고 여는 구멍이며
	 * 출시 전에 닫는다(§12-3 사용자 결정 "일단 그대로 신뢰").
	 */
	public EntitlementView recheck(UserId userId, SubscriptionSnapshot fromClient) {
		Subscription subscription = subscriptionRepository.findByUserId(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PAY_004));   // 구독 이력이 없는데 재확인할 게 없다

		if (!gate.stale(subscription, LocalDateTime.now(clock))) {
			return entitlementOf(userId);   // 멱등 — 이미 해소됐다
		}

		applyWriter.applyFromClient(
			subscription.getId(),
			parseStatus(fromClient.status()),
			toKstLocalDateTime(fromClient.expiresAt()),
			fromClient.autoRenew());
		return entitlementOf(userId);
	}

	/**
	 * U6·U7 소모 예약(§5-6). 기간권 사용자에게도 티켓을 발급한다 — 호출자(subtitle)가
	 * 이용권 종류로 분기하지 않아도 되게 하기 위해서다(§4).
	 *
	 * <p>⚠️ {@code PAY_007} 을 {@code PAY_001} 보다 <b>먼저</b> 본다 — 순서가 바뀌면 STALE 인
	 * 유료 사용자가 결제 유도 화면을 받는다(payment.md §6-7 이 금지한 화면).
	 */
	@Transactional
	public UsageTicketView reserve(UserId userId) {
		Subscription subscription = subscriptionRepository.findByUserId(userId).orElse(null);
		LocalDateTime now = LocalDateTime.now(clock);

		if (gate.stale(subscription, now)) {
			throw new BusinessException(ErrorCode.PAY_007);   // 상태 미확인 → recheck 유도
		}
		if (gate.accessible(subscription, now)) {
			// ✅-4ⓒ 기간권 우선 — 차감 없음
			return toView(usageTicketRepository.save(new UsageTicket(userId, TicketSource.SUBSCRIPTION)));
		}

		if (creditRepository.decrementIfPositive(userId) == 0) {
			throw new BusinessException(ErrorCode.PAY_001);   // 부족·행 없음 어느 쪽이든 결제 유도(§6-4)
		}
		return toView(usageTicketRepository.save(new UsageTicket(userId, TicketSource.CREDIT)));
	}

	/** 작업 성공 확정(§5-6) — RESERVED 아니면 무시(멱등). */
	@Transactional
	public void commit(UsageTicketId ticketId) {
		usageTicketRepository.transition(ticketId, TicketStatus.COMMITTED);
	}

	/**
	 * U8 작업 실패 되돌림(§5-6) — RESERVED 아니면 무시(멱등). 조건부 전이가 0행이면 잔량을
	 * 건드리지 않는다(C4 — 중복 release 의 +1 누출 차단). 이미 소모한 횟수권을 환불로 되돌리는
	 * 용도가 아니다(payment.md §4-8).
	 */
	@Transactional
	public void release(UsageTicketId ticketId) {
		UsageTicket ticket = usageTicketRepository.findById(ticketId).orElse(null);
		if (ticket == null) {
			return;   // 없는 티켓은 무시 — 멱등 계약의 연장
		}
		if (usageTicketRepository.transition(ticketId, TicketStatus.RELEASED) == 0) {
			return;   // 이미 처리됨
		}
		if (ticket.getSource() == TicketSource.CREDIT) {
			creditRepository.increment(ticket.getUserId());
		}
	}

	/**
	 * U9·U10·U11·U13 웹훅 처리(§5-4). U13(환불·회수)은 {@code current.status=REVOKED} 반영이 곧
	 * 즉시 회수다 — 단건 환불 감지는 ✅-8 로 "하지 않음"이라 구현 범위는 구독 웹훅뿐이다.
	 *
	 * <p>반영 실패가 수신 성공(204)을 막지 않는다 — 재전송 정책이 문서에 없어 재시도에 기댈 수 없다.
	 */
	public void handleWebhook(String authorizationHeader, WebhookEvent event) {
		// 0. U11 — 검증 실패는 처리하지 않는다(401)
		if (!authenticator.verify(authorizationHeader)) {
			throw new BusinessException(ErrorCode.AUTH_002);
		}

		// 1. U10 — 등록 검증 이벤트는 204 만 답한다
		if (WebhookEvent.TYPE_REGISTRATION_VERIFICATION.equals(event.eventType())) {
			return;
		}

		// 2. ✅-5 — 웹훅으로 구독을 만들지 않는다. 모르는 주문은 기록만 하고 무시
		Optional<Subscription> subscription = subscriptionRepository.findByOrderId(event.orderId());
		if (subscription.isEmpty()) {
			log.info("[payment] 모르는 주문의 웹훅 — 무시. orderId={}, changeReason={}",
				OrderIdMask.mask(event.orderId()), event.changeReason());
			return;
		}

		// 3. C5 — 과거·중복 이벤트는 버린다(occurredAt 비교뿐 — 이벤트 ID 가 없다)
		if (event.occurredAt() != null && subscription.get().alreadySeen(event.occurredAt())) {
			return;
		}

		// 4. 반영(§5-4④) — 실패해도 5(204)를 막지 않는다
		try {
			applyWriter.apply(subscription.get().getId(), event);
		} catch (RuntimeException e) {
			log.error("[payment] 웹훅 반영 실패 — 유실로 수용한다(재전송 정책 부재). changeReason={}",
				event.changeReason(), e);
		}
	}

	/** U3 멱등 200 ↔ U4 선점 PAY_005 판정(§5-2①). */
	private GrantResult replayOrReject(PaymentOrder order, UserId userId) {
		if (!order.ownedBy(userId)) {
			throw new BusinessException(ErrorCode.PAY_005);
		}
		return new GrantResult(true, order.getProductType(), entitlementOf(userId));
	}

	/**
	 * 경쟁 패배 후 재조회(§6-5 ④) — 새 트랜잭션이라 승자의 커밋을 반드시 본다. 이 근거는
	 * <b>UNIQUE 위반</b>일 때만 참이다(§6-5 · {@link #isUniqueViolation}).
	 * 주문 행이 없으면 null(order_id 경쟁이 아니다).
	 */
	private GrantResult settleOrderCompetition(UserId userId, String orderId) {
		return orderRepository.findByOrderId(orderId)
			.map(order -> replayOrReject(order, userId))
			.orElse(null);
	}

	/**
	 * 경쟁 판정 게이트(§6-3 의 의도) — JPA(saveAndFlush) 경로에서 UNIQUE 위반은
	 * {@code DuplicateKeyException} 이 아니라 {@code DataIntegrityViolationException} 으로 변환된다
	 * ({@code HibernateExceptionTranslator} — 라운드 1 실측. {@code DuplicateKeyException} 세분화는
	 * JdbcTemplate 의 SQLErrorCode 변환기 경로에만 있다). 원인 체인의 Hibernate
	 * {@link ConstraintViolationException} 을 찾아 <b>kind == UNIQUE</b> 로 좁혀,
	 * NOT NULL 위반·길이 초과가 경쟁 분기로 흘러 단서 없는 500 이 되는 것을 막는다.
	 */
	private static boolean isUniqueViolation(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE;
			}
		}
		return false;
	}

	private EntitlementView.SubscriptionView toSubscriptionView(Subscription subscription) {
		if (subscription == null) {
			return EntitlementView.SubscriptionView.none();
		}
		LocalDateTime expiresAt = subscription.getExpiresAt();
		OffsetDateTime offsetExpiresAt = expiresAt == null ? null : expiresAt.atOffset(KST_OFFSET);
		return new EntitlementView.SubscriptionView(
			subscription.getStatus().name(), offsetExpiresAt, subscription.isAutoRenew());
	}

	private UsageTicketView toView(UsageTicket ticket) {
		return new UsageTicketView(ticket.getId(), ticket.getSource());
	}

	/** 클라 문자열 → 상태 6종. 알 수 없는 값은 입력 오류다(COMMON_001). */
	private static SubscriptionStatus parseStatus(String raw) {
		try {
			return SubscriptionStatus.valueOf(raw);
		} catch (IllegalArgumentException unknownStatus) {
			throw new BusinessException(ErrorCode.COMMON_001);
		}
	}

	/** 오프셋 포함 입력 → KST 해석 {@code LocalDateTime} 저장(✅-7·§12-4). */
	private static LocalDateTime toKstLocalDateTime(OffsetDateTime value) {
		return value == null ? null : value.withOffsetSameInstant(KST_OFFSET).toLocalDateTime();
	}
}
