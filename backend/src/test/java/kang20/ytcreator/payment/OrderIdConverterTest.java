package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderIdConverterTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";

	private final OrderIdConverter converter = new OrderIdConverter();

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
