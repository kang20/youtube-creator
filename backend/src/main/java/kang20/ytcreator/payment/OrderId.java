package kang20.ytcreator.payment;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import kang20.ytcreator.shared.domain.ValueObject;

public final class OrderId extends ValueObject<OrderId> implements Serializable {

	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private final String value;

	public OrderId(String value) {
		requireNonNull(value, "주문 식별자는 비어 있을 수 없다");
		if (value.isBlank()) {
			throw new IllegalArgumentException("주문 식별자는 비어 있을 수 없다");
		}
		this.value = value;
	}

	public String raw() {
		return value;
	}

	public String masked() {
		if (value.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return value.substring(0, VISIBLE_PREFIX) + MASK;
	}

	@Override
	protected Object[] getEqualityFields() {
		return new Object[] {value};
	}

	@Override
	public String toString() {
		return masked();
	}
}
