package kang20.ytcreator.subscription.internal.handler.outbound.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 구독 저장소. 웹훅도 재확인도 <b>{@code orderId} 로</b> 찾는다 — 둘 다 특정 계약을 지목한다.
 *
 * <p>🔴 <b>{@code userId} 로 찾지 않는다.</b> {@code userId} 는 UNIQUE 가 아니라 재구독으로 계약이
 * 여러 개 쌓이고({@code Subscription} javadoc), "그중 어느 것"을 저장소가 고르기 시작하면 그 선택이
 * 호출자가 지목한 대상과 어긋날 수 있다.
 */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	Optional<Subscription> findByOrderId(OrderId orderId);

	/**
	 * 상태를 <b>고쳐 쓸 대상</b>을 행 잠금과 함께 읽는다({@code SELECT ... FOR UPDATE}).
	 *
	 * <p>🔴 <b>쓰기 경로는 예외 없이 이걸로 읽는다</b> — 웹훅도, 재확인도. 한쪽만 잠그면 잠금이
	 * 성립하지 않는다: 잠그지 않은 쪽이 옛 스냅샷을 들고 있다가 나중에 커밋하면 잠근 쪽의 갱신을
	 * 통째로 덮는다. 엔티티에 {@code @Version} 이 없어 막아 줄 두 번째 방어선도 없다.
	 *
	 * <p>🔴 <b>잠금이 순서 방어의 근거다.</b> 그냥 읽어서 비교한 뒤 쓰면 동시에 도착한 두 웹훅이
	 * 같은 {@code lastWebhookOccurredAt} 을 보고 둘 다 "더 나중"으로 판정해 통과하고, 나중에 커밋한
	 * 과거 웹훅이 최신 상태를 덮는다 — <b>만료가 되감기면 돈을 낸 사람이 막힌다.</b> 웹훅에는
	 * 이벤트 식별자도 재전송 정책도 없어 순서 판단의 근거가 발생 시각 하나뿐이고, 한 번 어긋나면
	 * 되돌릴 수단이 없다(new-domain/payment.md 구독 규칙).
	 *
	 * <p>격리 수준으로는 대신할 수 없다 — MySQL 기본(REPEATABLE READ)에서도 두 트랜잭션이 각자
	 * 스냅샷을 보므로 둘 다 통과한다. JVM 잠금으로도 안 된다 — 인스턴스가 2대다(deploy.md 블루-그린).
	 *
	 * <p>파생 쿼리를 쓰지 않는 이유: 메서드 이름의 {@code ForUpdate} 를 속성 이름으로 읽어 버린다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.orderId = :orderId")
	Optional<Subscription> findByOrderIdForUpdate(@Param("orderId") OrderId orderId);
}
