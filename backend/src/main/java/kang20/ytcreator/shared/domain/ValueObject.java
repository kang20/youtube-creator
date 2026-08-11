package kang20.ytcreator.shared.domain;

import java.util.Arrays;

/**
 * 값 동등성 공통 부모 — 타입 ID 계열의 기반. 규칙 정본: docs/rule/architecture.md
 * "모듈 간 데이터 참조 — 타입화된 기본키". 구현 선례: youngZZ {@code common/domain}(전 도메인 검증).
 *
 * <p>⚠️ {@code equals} 는 strict {@code getClass()} 비교다 — 구체 타입은 반드시 <b>{@code final}</b> 로
 * 선언한다. 하위 타입이 끼면 동등성이 비대칭으로 깨진다(architecture.md 함정 표).
 */
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
