package kang20.ytcreator.payment.internal.entity;

/**
 * 소모 티켓 상태(payment-design.md §3 — ✅-4ⓐ "생성 시 예약 → 완료 확정"의 실체).
 *
 * <p>전이는 <b>RESERVED 에서만, 한 번만</b> 일어난다 — 조건부 UPDATE 가 강제한다(§6-4 C4).
 * 중복 {@code release} 가 +1 을 두 번 하면 무료 이용권이 샌다(§6-2 함정 ⑥).
 */
public enum TicketStatus {
	RESERVED,
	COMMITTED,
	RELEASED
}
