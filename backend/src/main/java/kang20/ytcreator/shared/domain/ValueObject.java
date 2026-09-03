package kang20.ytcreator.shared.domain;

import java.util.Arrays;

public abstract class ValueObject<T extends ValueObject<T>> {

	@Override
	public boolean equals(Object other) {
		if (other == null) {
			return false;
		}

		if (!(other.getClass().equals(getClass()))) {
			return false;
		}

		@SuppressWarnings("unchecked")
		T typed = (T) other;
		return Arrays.equals(getEqualityFields(), typed.getEqualityFields());
	}

	@Override
	public int hashCode() {
		int hash = 17;
		for (Object each : getEqualityFields()) {
			hash = hash * 31 + (each == null ? 0 : each.hashCode());
		}
		return hash;
	}

	protected abstract Object[] getEqualityFields();
}
