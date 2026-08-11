package kang20.ytcreator.shared.domain;

import java.sql.Types;
import org.hibernate.HibernateException;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractClassJavaType;
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;
import org.hibernate.type.descriptor.java.MutabilityPlan;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;

/**
 * {@link LongTypeIdentifier} 계열의 Hibernate 매핑 어댑터 — DB 컬럼은 항상 BIGINT 다.
 * 규칙 정본: docs/rule/architecture.md "타입화된 기본키". 검증 환경: Boot 4 / Hibernate 7(youngZZ 선례).
 *
 * <p>엔티티 필드에 {@code @JavaType(구체JavaType.class)} 로 붙인다 — 연관관계가 아니라 값 컬럼이다.
 *
 * <p>⚠️ {@code wrap}/{@code fromString} 은 <b>리플렉션으로 {@code (Long)} 생성자</b>를 부른다 —
 * 컴파일 타임에 안 잡히므로 구체 ID 의 생성자 계약은 테스트가 지킨다(payment-design.md §10).
 */
public abstract class LongTypeIdentifierJavaType<T extends LongTypeIdentifier> extends AbstractClassJavaType<T> {

	protected LongTypeIdentifierJavaType(Class<T> clazz) {
		super(clazz);
	}

	@Override
	public MutabilityPlan<T> getMutabilityPlan() {
		return ImmutableMutabilityPlan.instance();
	}

	@Override
	public JdbcType getRecommendedJdbcType(JdbcTypeIndicators indicators) {
		return indicators.getTypeConfiguration()
			.getJdbcTypeRegistry()
			.getDescriptor(Types.BIGINT);
	}

	@Override
	public String toString(T value) {
		return value.longValue().toString();
	}

	@Override
	public T fromString(CharSequence string) {
		try {
			return getJavaType().getDeclaredConstructor(Long.class)
				.newInstance(Long.valueOf(string.toString()));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <X> X unwrap(T value, Class<X> type, WrapperOptions options) {
		if (value == null) {
			return null;
		}

		if (Long.class.isAssignableFrom(type)) {
			return (X) value.longValue();
		}

		throw new HibernateException(
			"Unknown unwrap conversion requested: " + getJavaType().getName() + " to " + type.getName());
	}

	@Override
	public <X> T wrap(X value, WrapperOptions options) {
		if (value == null) {
			return null;
		}

		if (getJavaType().isInstance(value)) {
			@SuppressWarnings("unchecked")
			T cast = (T) value;
			return cast;
		}

		if (value instanceof Long longValue) {
			try {
				return getJavaType().getDeclaredConstructor(Long.class)
					.newInstance(longValue);
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

		throw new HibernateException(
			"Unknown wrap conversion requested: " + value.getClass().getName() + " to " + getJavaType().getName());
	}
}
