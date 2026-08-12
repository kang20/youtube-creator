package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.dto.UsageTicketView;

/**
 * 결제·이용권 <b>소모 포트</b> — 작업 1건이 이용권을 소모하는 생애주기(예약→확정/되돌림)를 계약한다
 * (payment-design.md §5-6). 소비자: subtitle(작업 모듈 — 미구현, §4 확정 계약).
 *
 * <p>모듈 루트의 {@code *Port} 인터페이스가 모듈 간 공개 계약의 전부다.
 * 티켓 식별은 모듈 루트가 노출한 {@link UsageTicketId} 로만 한다.
 */
public interface PaymentConsumePort {

	/**
	 * U6·U7 소모 예약(§5-6). 기간권 사용자에게도 티켓을 발급한다 — 호출자(subtitle)가
	 * 이용권 종류로 분기하지 않아도 되게 하기 위해서다(§4).
	 */
	UsageTicketView reserve(UserId userId);

	/** 작업 성공 확정(§5-6) — RESERVED 아니면 무시(멱등). */
	void commit(UsageTicketId ticketId);

	/**
	 * U8 작업 실패 되돌림(§5-6) — RESERVED 아니면 무시(멱등). CREDIT 티켓만 잔량을 +1 한다.
	 * 이미 소모한 횟수권을 환불로 되돌리는 용도가 아니다(payment.md §4-8).
	 */
	void release(UsageTicketId ticketId);
}
