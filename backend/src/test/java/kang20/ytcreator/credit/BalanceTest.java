package kang20.ytcreator.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.credit.internal.entity.Balance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BalanceTest {

	@Test
	@DisplayName("음수 잔량은 만들 수 없다")
	void 음수_거부() {
		assertThatThrownBy(() -> new Balance(-1L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("비어 있는 잔량은 만들 수 없다")
	void null_거부() {
		assertThatThrownBy(() -> new Balance(null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	@DisplayName("0 은 유효한 잔량이다")
	void 영_허용() {
		assertThat(new Balance(0L).value()).isZero();
	}

	@Test
	@DisplayName("같은 개수면 같은 잔량이다")
	void 동등성() {
		assertThat(new Balance(3L)).isEqualTo(new Balance(3L));
		assertThat(new Balance(3L)).isNotEqualTo(new Balance(4L));
	}
}
