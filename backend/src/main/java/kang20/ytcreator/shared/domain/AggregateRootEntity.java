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

/** 등록만 한다 — 발행은 Spring Data 가 리포지토리 {@code save*} 직후에 하므로 저장이 터지면 발행되지 않는다. */
@MappedSuperclass
public abstract class AggregateRootEntity<A extends AggregateRootEntity<A>> extends BaseTimeEntity {

	@Transient
	private final transient List<Object> domainEvents = new ArrayList<>();

	protected <T> T registerEvent(T event) {
		Assert.notNull(event, "Domain event must not be null");
		domainEvents.add(event);
		return event;
	}

	@AfterDomainEventPublication
	protected void clearDomainEvents() {
		domainEvents.clear();
	}

	@DomainEvents
	protected Collection<Object> domainEvents() {
		return Collections.unmodifiableList(domainEvents);
	}

	@SuppressWarnings("unchecked")
	protected final A andEvent(Object event) {
		registerEvent(event);
		return (A) this;
	}
}
