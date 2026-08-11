package kang20.ytcreator.payment;

import kang20.ytcreator.shared.domain.LongTypeIdentifierJavaType;

/**
 * {@link UsageTicketId} 의 Hibernate 매핑 어댑터 — {@code usage_tickets.id BIGINT}.
 * IDENTITY 채번값을 {@code wrap} 이 감싼다(architecture.md "타입화된 기본키" JPA 매핑).
 */
public class UsageTicketIdJavaType extends LongTypeIdentifierJavaType<UsageTicketId> {

	public UsageTicketIdJavaType() {
		super(UsageTicketId.class);
	}
}
