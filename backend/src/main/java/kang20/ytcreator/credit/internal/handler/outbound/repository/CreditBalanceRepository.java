package kang20.ytcreator.credit.internal.handler.outbound.repository;

import java.time.LocalDateTime;

import kang20.ytcreator.credit.internal.entity.CreditBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 횟수권 잔량 저장소.
 *
 * <p>🔴 <b>증감은 읽어서 계산하지 않는다</b> — 원자 UPDATE 하나로 판정과 갱신을 한 문장에 넣는다.
 * 읽고-계산하고-저장하면 잔량 1 을 두 요청이 함께 읽고 둘 다 통과한다(new-domain/payment.md
 * 횟수권 규칙 · architecture.md "동시성 — 언제 쓰지 않는가").
 *
 * <p>⚠️ <b>네이티브인 이유</b>: {@code balance} 가 임베더블(Balance VO) 매핑이라 JPQL 산술
 * ({@code c.balance + 1})이 타입 오류가 된다. 네이티브 경로는 임베더블 인스턴스화를 타지 않으므로
 * 원시 {@code BIGINT} 산술로 직접 증감한다 — 사용자 확정 결정(2026-08-14)의 허용 경로다.
 * {@code user_id} 도 수동 언랩({@code UserId.longValue()})한 값으로 받는다(architecture.md
 * "네이티브 쿼리는 JavaType 을 안 탄다").
 *
 * <p>⚠️ {@code updated_at} 을 함께 갱신한다 — 벌크 UPDATE 는 JPA Auditing 을 타지 않는다
 * ({@code RefreshTokenRepository} 선례). 시각은 호출자가 {@code Clock} 에서 만들어 넘긴다.
 */
public interface CreditBalanceRepository extends JpaRepository<CreditBalance, Long> {

	/**
	 * 잔량 +1 — <b>행이 있을 때만</b> 원자적으로 올린다. 잠금 쓰기라 호출자 트랜잭션 안에서도
	 * 경쟁자의 커밋 행을 본다.
	 *
	 * @param userId {@code UserId.longValue()} 로 언랩한 값 — 네이티브 한정 예외다
	 * @param now    갱신 시각 — 호출자의 {@code Clock} 산출값
	 * @return 1 = 갱신됨, 0 = 행 없음(첫 지급 — 삽입 경로로 넘어간다)
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = "update credit_balance set balance = balance + 1, updated_at = :now where user_id = :userId",
		nativeQuery = true)
	int increment(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
