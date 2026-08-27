package kang20.ytcreator.payment.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.dto.GrantResult;

/**
 * 지급 포트 — 결제된 주문을 우리 이용권으로 바꾼다(new-domain/payment.md).
 * <b>지급을 부를 수 있는 유일한 진입</b>이다.
 *
 * <p><b>비공개 포트다</b>(inbound driving port) — 실질 소비자가 이 모듈의 {@code PaymentGrantController}
 * 하나뿐이라 모듈 루트가 아니라 여기 있다(architecture.md "공개 표면", R1). 다른 모듈은 지급을
 * 호출하지 않고 {@code ConsumableGranted}·{@code SubscriptionGranted} 를 구독한다. 밖에서 부를
 * 소비자가 생기면 그때 모듈 루트로 올린다.
 *
 * <p>구현체 {@code internal.service.PaymentService} 는 컨트롤러도 직접 참조할 수 없다(R5·R6).
 */
public interface PaymentPurchasePort {

	/**
	 * 지급한다 — 토스에 주문을 확인한 뒤에만 원장에 기록한다.
	 *
	 * <p>🔴 <b>멱등이다.</b> 같은 주문으로 몇 번 불러도 정확히 한 번만 지급되고, 재요청도 성공으로
	 * 답한다. 중복 호출은 장애가 아니라 <b>정상 경로</b>다 — 미결 주문 복원, 네트워크 재시도,
	 * 타임아웃 후 실은 성공한 경우가 모두 중복을 만든다.
	 *
	 * <p>🔴 <b>선점이다.</b> 이미 다른 사용자에게 귀속된 주문이면 거부한다. 다만 소유자는 끝까지
	 * 클라이언트의 주장이다 — 토스에 "이 주문이 누구 것인가"를 물어볼 수단이 없다.
	 *
	 * <p>⚠️ <b>{@code @Transactional} 밖에서 불러야 한다.</b> 안에서 부르면 ⓐ 토스 왕복이 DB
	 * 커넥션을 물고 네트워크를 기다리고, ⓑ 경쟁 판정을 위한 재조회가 호출자 트랜잭션의 스냅샷에
	 * 갇혀 경쟁자 행을 보지 못한다. 불변식은 트랜잭션이 아니라 {@code UNIQUE(order_id)} 가 지킨다.
	 *
	 * <p>⚠️ 30초 예산 안에 답해야 한다 — 넘기면 사용자에게 환불 안내 화면이 뜬다. 늦으면 붙잡지
	 * 않고 실패로 답하고 복원에 맡긴다.
	 *
	 * @param userId  선점 후보. 이미 다른 사용자의 주문이면 거부된다
	 * @param orderId 토스 주문 식별자. <b>원시 문자열이 아니라 {@link OrderId} 로 받는다</b> —
	 *                경계를 넘는 값이 문자열이면 마스킹 보장이 경계에서 끊긴다
	 * @throws kang20.ytcreator.shared.exception.BusinessException {@code PAY_005}(선점 위반) ·
	 *         {@code PAY_004}(우리 상품이 아니다) · {@code PAY_002}·{@code PAY_003}(지급할 수 없는 주문) ·
	 *         {@code PAY_006}(주문을 확인하지 못했다)
	 */
	GrantResult grant(UserId userId, OrderId orderId);
}
