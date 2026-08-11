package kang20.ytcreator.payment.dto;

/**
 * 소모 티켓의 재원(payment-design.md §3). 소모 우선순위(기간권 우선 — ✅-4ⓒ)는
 * {@code reserve()} 가 판정한다 — 호출자는 이 값으로 분기하지 않는다(§4).
 */
public enum TicketSource {

	/** 기간권 — 차감 없음. */
	SUBSCRIPTION,

	/** 횟수권 — 차감 1. {@code release} 시 +1 로 되돌린다(§5-6). */
	CREDIT
}
