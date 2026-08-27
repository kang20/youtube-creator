package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderId} ↔ 컬럼 매핑 어댑터 ({@link OrderIdConverter} javadoc).
 *
 * <p>정상 왕복은 {@code OrderPersistenceTest} 가 실제 JPA 로 본다. 여기서는 <b>null 방어</b>만 본다 —
 * 컬럼이 비어 있는 행(과거 데이터·옵셔널 매핑)에서 컨버터가 터지면 조회 자체가 죽는다.
 * JPA 경로로는 재현이 어렵다({@code order_id} 는 NOT NULL) — 그래서 어댑터를 직접 부른다.
 *
 * <p>⚠️ <b>이 테스트는 {@code payment} 모듈 루트 패키지에 있다</b>(2026-08-14 이동) —
 * {@code OrderIdConverter} 가 {@code internal/entity} 에서 모듈 루트로 올라갔기 때문이다
 * (subscription 이 {@code order_id} 를 저장하게 되어 공개 어댑터가 됐다. architecture.md
 * "타입화된 식별자" 판정 기준). 원래도 패키지-프라이빗 접근이 아니라 같은 패키지에 있었을 뿐이다.
 */
class OrderIdConverterTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";

	private final OrderIdConverter converter = new OrderIdConverter();

	/** 🔴 저장 컬럼에는 <b>원문</b>이 들어간다 — 마스킹 값이 들어가면 토스 조회 키가 깨진다. */
	@Test
	@DisplayName("컬럼에는 원문이 들어가고, 같은 값으로 되돌아온다")
	void 왕복() {
		String column = converter.convertToDatabaseColumn(new OrderId(RAW));

		assertThat(column).isEqualTo(RAW);
		assertThat(converter.convertToEntityAttribute(column)).isEqualTo(new OrderId(RAW));
	}

	@Test
	@DisplayName("null 은 양방향 모두 null 이다 — 빈 값에 방어가 뚫리지 않는다")
	void null_방어() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}
}
