package kang20.ytcreator.payment.dto;

import kang20.ytcreator.payment.UsageTicketId;

/**
 * 소모 예약 결과(payment-design.md §5-6). 호출자(subtitle)는 {@code ticketId} 로
 * {@code commit}/{@code release} 를 부른다.
 *
 * <p>⚠️ 호출자는 {@code source} 로 분기하지 않는다 — 소모 우선순위(기간권 우선)가
 * 두 모듈에 흩어지면 안 된다(§4). 진단·로그용 정보다.
 *
 * @param ticketId 티켓 식별자 — payment 가 밖에 노출하는 유일한 식별자
 * @param source   재원(SUBSCRIPTION=차감 없음 / CREDIT=차감 1)
 */
public record UsageTicketView(UsageTicketId ticketId, TicketSource source) {
}
