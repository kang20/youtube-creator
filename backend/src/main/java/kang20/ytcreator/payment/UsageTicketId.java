package kang20.ytcreator.payment;

import kang20.ytcreator.shared.domain.LongTypeIdentifier;

/**
 * 소모 티켓의 타입화된 PK — <b>subtitle 에 노출되는 유일한 payment 식별자</b>다
 * (payment-design.md §3). {@code commit}/{@code release} 호출에 쓰인다(§4).
 *
 * <p>⚠️ <b>리플렉션 계약</b>: 박싱 {@code Long} 1개짜리 public 생성자를 유지하고
 * 검증 로직을 넣지 마라 — Hibernate 하이드레이션이 이 생성자를 그대로 탄다(architecture.md 함정 표).
 */
public final class UsageTicketId extends LongTypeIdentifier {

	public UsageTicketId(Long id) {
		super(id);
	}
}
