package kang20.ytcreator.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Types;
import org.hibernate.HibernateException;
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.JdbcTypeIndicators;
import org.hibernate.type.spi.TypeConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 타입 ID 공통 부품 단위 (payment-design.md §10 {@code LongTypeIdentifierJavaTypeTest}).
 *
 * <p>덮는 것: wrap(Long/null/동일타입/미지원) · unwrap(Long/null/미지원) · fromString 라운드트립 ·
 * <b>리플렉션 생성자 실패 분기</b>(예외 던지는 픽스처) · ImmutableMutabilityPlan · BIGINT 매핑 ·
 * {@code ValueObject} 동등성(strict class 비교) — youngZZ 100% 커버 선례 이식(§4).
 *
 * <p>wrap/fromString 은 리플렉션으로 {@code (Long)} 생성자를 부른다 — 컴파일 타임에 안 잡히므로
 * 이 테스트가 그 계약의 감시자다(architecture.md 함정 표).
 */
class LongTypeIdentifierJavaTypeTest {

	/** 계약을 지키는 정상 픽스처 — public (Long) 생성자. */
	static final class ProbeId extends LongTypeIdentifier {
		public ProbeId(Long id) {
			super(id);
		}
	}

	static final class ProbeIdJavaType extends LongTypeIdentifierJavaType<ProbeId> {
		ProbeIdJavaType() {
			super(ProbeId.class);
		}
	}

	/** 생성자가 던지는 픽스처 — 리플렉션 실패 분기(IllegalStateException 래핑)를 태운다. */
	static final class BrokenId extends LongTypeIdentifier {
		public BrokenId(Long id) {
			super(id);
			throw new IllegalArgumentException("계약 위반 재현 — 생성자에 검증을 넣으면 이렇게 죽는다");
		}
	}

	static final class BrokenIdJavaType extends LongTypeIdentifierJavaType<BrokenId> {
		BrokenIdJavaType() {
			super(BrokenId.class);
		}
	}

	/** strict getClass 비교 검증용 — 같은 값의 다른 구체 타입. */
	static final class OtherId extends LongTypeIdentifier {
		public OtherId(Long id) {
			super(id);
		}
	}

	private final ProbeIdJavaType javaType = new ProbeIdJavaType();

	// ── wrap ────────────────────────────────────────────────────────────

	/** JDBC BIGINT → 타입 ID. Hibernate 하이드레이션이 타는 경로다 */
	@Test
	@DisplayName("wrap 은 Long 을 (Long) 생성자로 감싼다")
	void wrap_Long() {
		assertThat(javaType.wrap(42L, null)).isEqualTo(new ProbeId(42L));
	}

	@Test
	@DisplayName("wrap 은 null 을 null 로 통과시킨다")
	void wrap_null() {
		assertThat(javaType.wrap(null, null)).isNull();
	}

	@Test
	@DisplayName("wrap 은 이미 같은 타입이면 그대로 돌려준다")
	void wrap_동일타입() {
		ProbeId id = new ProbeId(7L);
		assertThat(javaType.wrap(id, null)).isSameAs(id);
	}

	@Test
	@DisplayName("wrap 은 지원하지 않는 타입에 HibernateException 을 던진다")
	void wrap_미지원() {
		assertThatThrownBy(() -> javaType.wrap("42", null))
			.isInstanceOf(HibernateException.class)
			.hasMessageContaining("Unknown wrap conversion");
	}

	/** 리플렉션 계약 위반(생성자가 던짐) — IllegalStateException 으로 래핑된다 */
	@Test
	@DisplayName("생성자가 예외를 던지면 wrap 은 IllegalStateException 으로 래핑한다")
	void wrap_리플렉션_실패() {
		assertThatThrownBy(() -> new BrokenIdJavaType().wrap(1L, null))
			.isInstanceOf(IllegalStateException.class);
	}

	// ── unwrap ──────────────────────────────────────────────────────────

	@Test
	@DisplayName("unwrap 은 Long 요청에 내부 값을 돌려준다")
	void unwrap_Long() {
		assertThat(javaType.unwrap(new ProbeId(42L), Long.class, null)).isEqualTo(42L);
	}

	@Test
	@DisplayName("unwrap 은 null 을 null 로 통과시킨다")
	void unwrap_null() {
		assertThat((Object) javaType.unwrap(null, Long.class, null)).isNull();
	}

	@Test
	@DisplayName("unwrap 은 지원하지 않는 타입 요청에 HibernateException 을 던진다")
	void unwrap_미지원() {
		assertThatThrownBy(() -> javaType.unwrap(new ProbeId(1L), String.class, null))
			.isInstanceOf(HibernateException.class)
			.hasMessageContaining("Unknown unwrap conversion");
	}

	// ── fromString / toString ───────────────────────────────────────────

	@Test
	@DisplayName("toString → fromString 라운드트립이 값을 보존한다")
	void 문자열_라운드트립() {
		ProbeId original = new ProbeId(123L);

		String text = javaType.toString(original);

		assertThat(text).isEqualTo("123");
		assertThat(javaType.fromString(text)).isEqualTo(original);
	}

	@Test
	@DisplayName("생성자가 예외를 던지면 fromString 도 IllegalStateException 으로 래핑한다")
	void fromString_리플렉션_실패() {
		assertThatThrownBy(() -> new BrokenIdJavaType().fromString("1"))
			.isInstanceOf(IllegalStateException.class);
	}

	// ── Hibernate 계약 ──────────────────────────────────────────────────

	/** 타입 ID 는 불변 — 더티체킹 스냅샷 복사가 필요 없다 */
	@Test
	@DisplayName("MutabilityPlan 은 Immutable 이다")
	void 불변_계획() {
		assertThat(javaType.getMutabilityPlan()).isSameAs(ImmutableMutabilityPlan.instance());
	}

	/** DB 컬럼은 항상 BIGINT — DDL 의 user_id BIGINT 와 이 매핑이 짝이다(payment-design.md §3-1) */
	@Test
	@DisplayName("권장 JDBC 타입은 BIGINT 다")
	void BIGINT_매핑() {
		TypeConfiguration typeConfiguration = new TypeConfiguration();
		JdbcTypeIndicators indicators = new JdbcTypeIndicators() {
			@Override
			public TypeConfiguration getTypeConfiguration() {
				return typeConfiguration;
			}

			@Override
			public org.hibernate.dialect.Dialect getDialect() {
				return new org.hibernate.dialect.H2Dialect();
			}
		};

		JdbcType jdbcType = javaType.getRecommendedJdbcType(indicators);

		assertThat(jdbcType.getJdbcTypeCode()).isEqualTo(Types.BIGINT);
	}

	// ── ValueObject 동등성 (shared/domain 공통 부모) ─────────────────────

	/** ValueObject — 값 동등성. 같은 값이면 같고 해시도 같다 */
	@Test
	@DisplayName("같은 값의 같은 타입은 동등하고 해시도 같다")
	void 값_동등성() {
		assertThat(new ProbeId(1L))
			.isEqualTo(new ProbeId(1L))
			.hasSameHashCodeAs(new ProbeId(1L))
			.isNotEqualTo(new ProbeId(2L));
	}

	/** ValueObject — strict getClass 비교. 다른 도메인의 같은 Long 값과 섞이면 안 된다(§2-1 쟁점 1) */
	@Test
	@DisplayName("같은 값이라도 타입이 다르면 동등하지 않다 — 도메인 혼용 차단")
	void 타입이_다르면_불동등() {
		assertThat(new ProbeId(1L)).isNotEqualTo(new OtherId(1L));
		assertThat(new ProbeId(1L)).isNotEqualTo(null);
		assertThat(new ProbeId(1L)).isNotEqualTo(1L);
	}

	/** null 값 필드의 hashCode 분기(ValueObject) + toString 형식 */
	@Test
	@DisplayName("null 값도 동등성·해시·toString 이 안전하다")
	void null_값_안전() {
		assertThat(new ProbeId(null))
			.isEqualTo(new ProbeId(null))
			.hasSameHashCodeAs(new ProbeId(null));
		assertThat(new ProbeId(null).toString()).isEqualTo("ProbeId(null)");
	}

	/** 진단 표기 — "타입명(값)" (LongTypeIdentifier.toString) */
	@Test
	@DisplayName("toString 은 '타입명(값)' 형식이다")
	void toString_형식() {
		assertThat(new ProbeId(9L).toString()).isEqualTo("ProbeId(9)");
		assertThat(new ProbeId(9L).longValue()).isEqualTo(9L);
	}
}
