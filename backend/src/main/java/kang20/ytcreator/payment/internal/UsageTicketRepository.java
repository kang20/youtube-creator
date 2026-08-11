package kang20.ytcreator.payment.internal;

import kang20.ytcreator.payment.UsageTicketId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 소모 티켓 저장소. 상태 전이는 <b>조건부 UPDATE</b>로만 한다(payment-design.md §6-4 C4).
 */
public interface UsageTicketRepository extends JpaRepository<UsageTicket, UsageTicketId> {

	/**
	 * RESERVED 에서만 전이한다 — 반환 0 = 이미 처리됨(멱등). 호출자는 잔량을 건드리지 않고
	 * 반환한다(§5-6 — 중복 release 의 +1 누출 차단).
	 */
	@Modifying(clearAutomatically = true)
	@Query("update UsageTicket t set t.status = :to where t.id = :id"
		+ " and t.status = kang20.ytcreator.payment.internal.TicketStatus.RESERVED")
	int transition(@Param("id") UsageTicketId id, @Param("to") TicketStatus to);
}
