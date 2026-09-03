package kang20.ytcreator.shared.domain;

import java.io.Serializable;

/** 하위 타입은 박싱 {@code Long} 1개짜리 public 생성자를 유지해야 한다 — {@link LongTypeIdentifierJavaType} 이 리플렉션으로 부른다. */
public abstract class LongTypeIdentifier extends ValueObject<LongTypeIdentifier> implements Serializable {

	private final Long id;

	protected LongTypeIdentifier(Long id) {
		this.id = id;
	}

	public Long longValue() {
		return id;
	}

	@Override
	protected Object[] getEqualityFields() {
		return new Object[] {id};
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName() + "(" + longValue() + ")";
	}
}
