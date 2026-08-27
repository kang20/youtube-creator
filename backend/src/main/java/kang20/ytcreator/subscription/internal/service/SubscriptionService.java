package kang20.ytcreator.subscription.internal.service;

import static kang20.ytcreator.shared.exception.ErrorCode.COMMON_001;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.support.UniqueRace;
import kang20.ytcreator.subscription.internal.port.SubscriptionGrantPort;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.entity.SubscriptionStatus;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.subscription.internal.service.support.AccessGate;
import kang20.ytcreator.subscription.internal.service.support.SubscriptionStartWriter;
import kang20.ytcreator.subscription.internal.service.support.WebhookEventInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService implements SubscriptionGrantPort, SubscriptionStatusPort {
	private final SubscriptionRepository subscriptionRepository;
	private final SubscriptionStartWriter startWriter;
	private final AccessGate accessGate;
	private final WebhookEventInspector eventInspector;
	private final Clock clock;

	/** 월 주기는 30일 확정(payment.md 참고자료 ⑥-2) + 여유 1일. 짧게 잡으면 돈 낸 사람을 막는다. */
	private static final int ESTIMATED_PERIOD_DAYS = 31;

	@Override
	public void start(UserId userId, OrderId orderId) {
		if (subscriptionRepository.findByOrderId(orderId).isPresent()) {
			return;
		}

		var estimatedExpiresAt = LocalDateTime.now(clock).plusDays(ESTIMATED_PERIOD_DAYS);

		UniqueRace.firstWriterWins(
			() -> {
				startWriter.open(Subscription.start(orderId, userId, estimatedExpiresAt));
				return true;
			},
			() -> subscriptionRepository.findByOrderId(orderId)
				.map(winner -> true),
			orderId
		);
	}

	@Override
	@Transactional
	public void handleWebhook(String secret, WebhookEvent event) {
		if (!eventInspector.shouldApply(secret, event)) {
			log.info("[subscription] 콜백 URL 등록 검증 웹훅 수신 — 본문 처리 없음");
			return;
		}

		Subscription known = subscriptionRepository
			.findByOrderIdForUpdate(new OrderId(event.orderId()))
			.orElse(null);

		if (known == null) {
			log.info("[subscription] 모르는 주문의 웹훅 — 무시한다. orderId={}, changeReason={}",
				event.orderId(), event.changeReason());
			return;
		}

		var current = event.subscription().current();
		var previous = event.subscription().previous();
		var status = SubscriptionStatus.from(current.status())
			.orElseThrow(() -> new BusinessException(COMMON_001));

		if (isMissedWebhook(previous, known)) {
			log.warn("[subscription] 웹훅 직전 상태 불일치 — 그 사이 웹훅을 놓쳤다. previous={}, ours={}, changeReason={}",
				previous.status(), known.getStatus(), event.changeReason());
		}

		if (!known.applyWebhook(status, current, event.occurredAt())) {
			log.info("[subscription] 이미 반영한 것보다 과거인 웹훅 — 무시한다. occurredAt={}, changeReason={}",
				event.occurredAt(), event.changeReason());
		}

		subscriptionRepository.save(known);
	}

	private static boolean isMissedWebhook(WebhookEvent.Snapshot previous, Subscription known) {
		return previous != null && known.hasMissedWebhook(previous.status());
	}

	@Override
	@Transactional
	public void recheck(UserId userId, SubscriptionSnapshot fromClient) {
		SubscriptionStatus status = SubscriptionStatus.from(fromClient.status())
			.orElseThrow(() -> new BusinessException(COMMON_001));

		var subscription = subscriptionRepository
			.findByOrderIdForUpdate(new OrderId(fromClient.orderId()))
			.orElse(null);

		if (subscription == null) {
			log.info("[subscription] 모르는 주문의 재확인 — 무동작. orderId={}", fromClient.orderId());
			return;
		}

		if (!subscription.getUserId().equals(userId)) {
			log.warn("[subscription] 남의 주문으로 온 재확인 — 무동작. userId={}, orderId={}",
				userId, fromClient.orderId());
			return;
		}

		if (!accessGate.stale(subscription, LocalDateTime.now(clock))) {
			return;
		}

		subscription.applyClientSnapshot(status, fromClient.expiresAt(), fromClient.autoRenew());

		log.warn("[subscription] 재확인으로 임시 보정 — 클라이언트 값이며 정본이 아니다. userId={}, orderId={}, status={}",
			userId, fromClient.orderId(), status);

		subscriptionRepository.save(subscription);
	}
}
