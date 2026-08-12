package kang20.ytcreator.payment.internal.service.support;
import kang20.ytcreator.shared.support.Support;

import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;
import kang20.ytcreator.payment.internal.handler.outbound.repository.SubscriptionRepository;

import java.time.LocalDateTime;
import kang20.ytcreator.payment.dto.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 반영 쓰기 전용 빈 — 웹훅 {@code apply}(§5-4)와 recheck {@code applyFromClient}(§5-5).
 *
 * <p>⚠️ 별도 빈인 이유는 {@code GrantWriter} 와 같다 — {@code PaymentService} 안에서
 * 자기 호출하면 프록시를 우회해 트랜잭션이 안 걸린다(payment-design.md §4).
 */
@Component
@Support
public class SubscriptionApplyWriter {

	private static final Logger log = LoggerFactory.getLogger(SubscriptionApplyWriter.class);

	private final SubscriptionRepository subscriptionRepository;

	public SubscriptionApplyWriter(SubscriptionRepository subscriptionRepository) {
		this.subscriptionRepository = subscriptionRepository;
	}

	/**
	 * 웹훅 반영(§5-4④) — 판정 기준은 {@code current} 의 status·expiresAt·autoRenew 세 값이다.
	 * <b>{@code changeReason} 으로 분기하지 않는다</b>(RESTARTED 인데 autoRenew=false 사례).
	 * {@code accessGranted} 는 쓰지 않는다 — 산식이 문서에 없다.
	 *
	 * <p>{@code previous} 가 우리 상태와 불일치하면 WARN — 그 사이 웹훅을 놓친 것이다(유실 감지).
	 * 반영은 그대로 진행한다. 보정은 §5-5(recheck)가 맡는다.
	 */
	@Transactional
	public void apply(Long subscriptionId, WebhookEvent event) {
		Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
		WebhookEvent.Snapshot current = event.subscription().current();
		WebhookEvent.Snapshot previous = event.subscription().previous();

		if (previous != null && previous.status() != null
				&& !subscription.getStatus().name().equals(previous.status())) {
			log.warn("[payment] 웹훅 previous 불일치 — 유실 의심. previous={}, ours={}, changeReason={}",
				previous.status(), subscription.getStatus(), event.changeReason());
		}

		subscription.applyWebhook(
			SubscriptionStatus.valueOf(current.status()),
			current.expiresAt(),
			current.autoRenew() != null ? current.autoRenew() : subscription.isAutoRenew(),
			event.occurredAt());
	}

	/**
	 * recheck 반영(§5-5) — 클라 스냅샷은 임시 보정이다({@code expiresAtEstimated=false}).
	 * ⚠️ {@code lastWebhookOccurredAt} 은 엔티티 메서드가 건드리지 않는다(§4-7-1⑦) —
	 * 웹훅이 recheck 결과를 정상적으로 덮을 수 있어야 한다: <b>웹훅이 정본, recheck 는 임시 보정.</b>
	 */
	@Transactional
	public void applyFromClient(Long subscriptionId, SubscriptionStatus status, LocalDateTime expiresAt,
			boolean autoRenew) {
		Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
		subscription.applyClientSnapshot(status, expiresAt, autoRenew);
	}
}
