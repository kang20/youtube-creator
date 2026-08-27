package kang20.ytcreator.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.credit.internal.entity.Balance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C6 — Balance VO 불변식 (new-domain/payment.md [횟수권 애그리거트] "잔량은 절대 음수가 되지
 * 않는다" · 잔량(Balance) VO "음수가 될 수 없다").
 *
 * <p>불변식이 갱신 경로(원자 UPDATE 조건)에만 있으면 생성 경로가 새는 곳이 된다 — 그래서
 * 생성자 검증을 여기서 고정한다. 읽기 경로(하이드레이션)까지 같은 생성자를 타는지는
 * {@link BalanceHydrationTest} 가 본다.
 */
class BalanceTest {

	/** C6 — 음수 생성 거부 (payment.md "잔량은 절대 음수가 되지 않는다") */
	@Test
	@DisplayName("음수 잔량은 만들 수 없다")
	void 음수_거부() {
		assertThatThrownBy(() -> new Balance(-1L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	/** C6 — null 도 생성 거부. 잔량 없음은 "행이 없다"로 표현하지 null 값으로 표현하지 않는다 */
	@Test
	@DisplayName("비어 있는 잔량은 만들 수 없다")
	void null_거부() {
		assertThatThrownBy(() -> new Balance(null))
			.isInstanceOf(NullPointerException.class);
	}

	/** C6 — 0 허용. "행이 없는 것과 잔량이 0인 것은 같은 뜻"이므로 0 은 유효한 값이다 */
	@Test
	@DisplayName("0 은 유효한 잔량이다")
	void 영_허용() {
		assertThat(new Balance(0L).value()).isZero();
	}

	/** C6 — 값 객체 동등성: 같은 개수면 같은 잔량이다 */
	@Test
	@DisplayName("같은 개수면 같은 잔량이다")
	void 동등성() {
		assertThat(new Balance(3L)).isEqualTo(new Balance(3L));
		assertThat(new Balance(3L)).isNotEqualTo(new Balance(4L));
	}
}
