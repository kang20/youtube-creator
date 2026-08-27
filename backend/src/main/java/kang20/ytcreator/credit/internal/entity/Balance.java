package kang20.ytcreator.credit.internal.entity;

import static java.util.Objects.requireNonNull;

import jakarta.persistence.Embeddable;

@Embeddable
public record Balance(Long value) {

	public Balance {
		requireNonNull(value, "잔량은 비어 있을 수 없다");
		if (value < 0) {
			throw new IllegalArgumentException("잔량은 음수가 될 수 없다");
		}
	}
}
