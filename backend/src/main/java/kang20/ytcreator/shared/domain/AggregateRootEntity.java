package kang20.ytcreator.shared.domain;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;
import org.springframework.util.Assert;

/**
 * 도메인 이벤트를 모을 수 있는 <b>애그리거트 루트</b>. 상속 자체가 "이 엔티티가 루트다"라는 표시다.
 *
 * <p><b>왜 발행이 아니라 등록인가.</b> 도메인 사건이 성립하는 시점(애그리거트 안)과 그 사실을 밖에
 * 알려도 되는 시점(선점·검증을 통과한 뒤)은 다르다. 두 시점을 붙이면 아직 확정되지 않은 사실까지
 * 새어 나간다. 그래서 애그리거트는 {@link #registerEvent(Object)} 로 모으기만 한다.
 *
 * <p><b>누가 발행하는가.</b> Spring Data 의 {@code @DomainEvents} 메커니즘이다.
 * {@code EventPublishingRepositoryProxyPostProcessor} 가 리포지토리 호출을 가로채,
 * <b>파라미터가 1개이면서 이름이 {@code save} 로 시작하거나 삭제 메서드</b>인 경우
 * {@link #domainEvents()} 를 읽어 발행하고 {@link #clearDomainEvents()} 로 비운다.
 * 실측(spring-data-commons 4.0.5) 결과 다음이 확인됐다.
 * <ul>
 *   <li>{@code saveAndFlush(entity)} 도 이 판별을 통과한다 — 이름이 {@code save} 로 시작하고
 *       파라미터가 1개이기 때문이다.</li>
 *   <li>이벤트는 리포지토리에 <b>넘긴 인자 인스턴스</b>에서 찾는다(반환값이 아니다).</li>
 *   <li>발행은 {@code invocation.proceed()} <b>이후</b>다 — 저장/flush 가 예외로 끝나면
 *       발행 지점에 도달하지 않는다.</li>
 * </ul>
 * 세 번째 사실이 우리 불변식을 지켜 준다: {@code UNIQUE} 선점에 <b>진</b> 요청은 flush 에서
 * 제약 위반으로 터지므로 이벤트를 내지 않는다. "선점에 이긴 요청만 발행한다"가 코드가 아니라
 * 인프라의 순서로 보장된다.
 *
 * <p>쓰기 빈이 인출 메서드를 부르는 방식(수동 발행)을 쓰지 않는 이유: 부르는 것을 잊으면
 * 이벤트가 <b>조용히 유실된다.</b> 저장에 올라타면 그 구멍이 사라진다.
 *
 * <p><b>{@code AbstractAggregateRoot} 를 상속하지 않는 이유.</b> 우리 엔티티는 감사 시각을 위해
 * 이미 {@link BaseTimeEntity} 를 상속해 단일 상속이 소진됐다. Spring Data 가 javadoc 에서
 * 안내하는 대로("build your own base class and use the annotations directly") 구현은 그대로
 * 본뜨고 애노테이션({@link DomainEvents}·{@link AfterDomainEventPublication})만 직접 붙인다.
 * 발행 메커니즘은 상속이 아니라 애노테이션으로 찾으므로 동작은 동일하다.
 *
 * @param <A> 자기 자신의 구체 타입(self-type). {@link #andEvent(Object)} 가 {@code A} 를
 *            돌려주어 {@code return new Order(...).andEvent(...)} 처럼 정적 팩토리에서
 *            바로 반환할 수 있게 하는 것이 이 제네릭의 유일한 목적이다.
 */
@MappedSuperclass
public abstract class AggregateRootEntity<A extends AggregateRootEntity<A>> extends BaseTimeEntity {

	/**
	 * 등록됐지만 아직 발행되지 않은 도메인 이벤트들.
	 *
	 * <p>{@code @Transient} 이므로 매핑되지 않는다 — 스키마(DDL)에 영향이 없다.
	 * 필드 초기화자는 하위 엔티티의 no-arg 생성자가 부르는 {@code super()} 에서도 실행되므로,
	 * Hibernate 가 조회로 하이드레이션한 인스턴스에서도 이 목록은 절대 {@code null} 이 아니다
	 * (그리고 비어 있다 — 조회로 살아난 애그리거트는 이벤트를 내지 않는다).
	 * 따라서 {@code final} 로 두고 지연 초기화(null 체크) 방어 코드를 두지 않는다 —
	 * 도달 불가능한 분기를 만들지 않기 위함이다.
	 *
	 * <p>공개 접근자를 만들지 않는다 — 가변 컬렉션이 밖으로 새면 호출자가 임의로 조작할 수 있다.
	 * 하위 엔티티가 Lombok 클래스 레벨 {@code @Getter} 를 쓰더라도 상위 클래스의 이 필드에는
	 * 접근자가 생성되지 않는다.
	 */
	@Transient
	private final transient List<Object> domainEvents = new ArrayList<>();

	/**
	 * 도메인 이벤트를 등록한다. 발행하지 않는다 — 발행은 리포지토리 {@code save*} 직후다.
	 *
	 * @param event 등록할 이벤트. {@code null} 이면 안 된다
	 * @return 넘긴 이벤트 그대로
	 */
	protected <T> T registerEvent(T event) {
		Assert.notNull(event, "Domain event must not be null");
		domainEvents.add(event);
		return event;
	}

	/**
	 * 발행이 끝난 뒤 Spring Data 가 호출한다. 등록 목록을 비워 재발행을 막는다.
	 */
	@AfterDomainEventPublication
	protected void clearDomainEvents() {
		domainEvents.clear();
	}

	/**
	 * Spring Data 가 발행할 이벤트를 여기서 읽어 간다.
	 *
	 * @return 아직 발행되지 않은 이벤트들의 <b>불변 뷰</b> — 호출자가 내부 목록을 건드릴 수 없다
	 */
	@DomainEvents
	protected Collection<Object> domainEvents() {
		return Collections.unmodifiableList(domainEvents);
	}

	/**
	 * 이벤트를 등록하고 <b>자기 자신</b>을 돌려준다 — 정적 팩토리에서 한 문장으로 쓰기 위한 것이다.
	 *
	 * <p>{@code final} 인 이유: 하위 애그리거트가 수집 규약을 바꾸면 발행이 갈라진다.
	 */
	@SuppressWarnings("unchecked")
	protected final A andEvent(Object event) {
		registerEvent(event);
		return (A) this;
	}
}
