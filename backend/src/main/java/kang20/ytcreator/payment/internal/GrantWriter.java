package kang20.ytcreator.payment.internal;

import java.time.LocalDateTime;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.ProductType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급 쓰기 전용 빈 — 주문 원장 + (횟수권 +1 | 기간권 생성)을 <b>한 트랜잭션</b>에 넣는다
 * (payment-design.md §6-5). 둘은 반드시 함께 커밋되거나 함께 롤백돼야 한다 — 주문만 남으면
 * 돈을 내고 못 쓰고, 잔량만 오르면 멱등이 깨져 재요청 때 또 오른다.
 *
 * <p>⚠️ <b>{@code PaymentService} 로 합치지 마라.</b> 자기 호출은 프록시를 우회해
 * {@code REQUIRES_NEW} 가 걸리지 않고, UNIQUE 위반이 호출자 트랜잭션을 오염시킨다(§6-5).
 *
 * <p>⚠️ <b>{@code AuthService} 를 주입받지 마라.</b> {@code register} 를 REQUIRES_NEW 안에서
 * 부르면 auth-design §6-4 의 전제(트랜잭션 밖 호출)가 깨진다 — {@code UserId} 해석은
 * 컨트롤러에서 끝났고 여기는 이미 해석된 값만 받는다(§6-5 · §10 이 감시한다).
 */
@Component
public class GrantWriter {

	/** 최초 구독 만료 추정 — 결제일 + 31일(§4-7-1④). 다음 웹훅 정본이 덮는다(estimated=true). */
	private static final int ESTIMATED_SUBSCRIPTION_DAYS = 31;

	private final PaymentOrderRepository orderRepository;
	private final CreditBalanceRepository creditRepository;
	private final SubscriptionRepository subscriptionRepository;

	public GrantWriter(PaymentOrderRepository orderRepository, CreditBalanceRepository creditRepository,
			SubscriptionRepository subscriptionRepository) {
		this.orderRepository = orderRepository;
		this.creditRepository = creditRepository;
		this.subscriptionRepository = subscriptionRepository;
	}

	/**
	 * 지급 원자 쓰기(§6-5). {@code UNIQUE(order_id)} 판정이 여기서 난다 — 위반 시
	 * {@code DataIntegrityViolationException}(원인 체인: Hibernate {@code ConstraintViolationException},
	 * kind=UNIQUE)이 그대로 나가고 <b>이 트랜잭션 전체가 롤백된다.</b> JPA 경로는 UNIQUE 위반을
	 * {@code DuplicateKeyException} 으로 세분화하지 않는다(라운드 1 실측).
	 * 잡아서 살리려 하지 마라 — 같은 트랜잭션에서 잡으면 rollback-only 로 못 살린다(§6-2 함정 ②).
	 * 경쟁 판정은 트랜잭션 밖의 {@code PaymentService} 가 한다(§6-3).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public PaymentOrder grant(UserId userId, String orderId, String sku, ProductType type, LocalDateTime now) {
		PaymentOrder order = orderRepository.saveAndFlush(new PaymentOrder(orderId, userId, sku, type));

		if (type == ProductType.CONSUMABLE) {
			increaseCredit(userId);
		} else {
			subscriptionRepository.saveAndFlush(
				new Subscription(orderId, userId, now.plusDays(ESTIMATED_SUBSCRIPTION_DAYS)));
		}

		return order;
	}

	/**
	 * §6-4 C2 — UPDATE 먼저, 0행이면 insert. insert 가 행 생성 경쟁에서 지면
	 * UNIQUE 위반({@code DataIntegrityViolationException})으로 트랜잭션 전체가 롤백되고,
	 * {@code PaymentService} 가 <b>새 트랜잭션으로 정확히 1회 재시도</b>한다 — 행은 한 번만
	 * 생기므로 재시도의 {@code increment} 는 반드시 1행이다(§6-4).
	 */
	private void increaseCredit(UserId userId) {
		if (creditRepository.increment(userId) == 0) {
			creditRepository.saveAndFlush(new CreditBalance(userId, 1));
		}
	}
}
