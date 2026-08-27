package kang20.ytcreator.subscription.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;

/**
 * 구독 개시 포트 — 검증된 구독 주문으로 계약 한 행을 연다
 * (new-domain/payment.md 구독 애그리거트 {@code start()}).
 *
 * <p><b>실질 소비자는 이 모듈의 이벤트 리스너다</b>(inbound driving port) — payment 가 발행한
 * {@code SubscriptionGranted} 를 {@code internal/handler/inbound} 의 리스너가 받아 이 포트로
 * 들어온다. 다른 모듈이 직접 부를 일은 아직 없어서 <b>모듈 루트가 아니라 여기 있다</b>
 * (architecture.md "공개 표면", R1).
 *
 * <p>🔴 <b>구독이 생기는 경로는 이것 하나다.</b> 웹훅으로는 구독을 만들지 않는다 — 이 규칙이
 * 위조 웹훅의 최대 피해를 "없는 구독 생성"에서 "이미 결제한 사람의 상태 흔들기"로 줄인다.
 *
 * <p>⚠️ <b>호출자는 동기 리스너다</b> — 발행자(payment)의 원장 트랜잭션 안에서 들어온다. 그래서 포트
 * 진입점에 {@code @Transactional} 을 붙이지 않는다. 삽입 자체는 {@code REQUIRES_NEW} 쓰기 빈으로
 * 분리돼 있어, UNIQUE 위반이 호출자(=원장) 트랜잭션을 rollback-only 로 만들지 않는다.
 */
public interface SubscriptionGrantPort {

	/**
	 * 구독을 개시한다 — 활성·추정 만료·웹훅 미수신으로 태어난다.
	 *
	 * <p>🔴 <b>같은 주문으로 다시 불러도 구독은 하나다.</b> 근거는 {@code UNIQUE(order_id)} 다 —
	 * 조회해서 없으면 삽입하는 방식은 근거가 되지 않는다(동시에 들어온 둘이 함께 통과한다).
	 *
	 * <p><b>같은 사용자의 두 번째 구독은 거부하지 않는다</b> — 다른 주문이면 다른 계약이다.
	 * 만료·해지 뒤 재구독이 정상 경로이고, 이력은 여러 행으로 쌓인다.
	 *
	 * @param userId  구독 소유자
	 * @param orderId 최초 구독 주문 식별자 — 저장되고, <b>웹훅이 이 값으로 구독을 찾아온다</b>
	 */
	void start(UserId userId, OrderId orderId);
}
