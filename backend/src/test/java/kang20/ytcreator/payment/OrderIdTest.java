package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import kang20.ytcreator.payment.OrderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 주문 식별자 값 객체 계약 (new-domain/payment.md 주문 애그리거트).
 *
 * <p>덮는 것: 값 동등성 · <b>final 선언</b>(ValueObject strict equals 전제) ·
 * <b>toString 마스킹</b>(U14 비노출의 구조적 방어) · 빈 값 거부.
 */
class OrderIdTest {

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";

	/** ValueObject 는 strict getClass 비교라 하위 타입이 끼면 동등성이 비대칭으로 깨진다. */
	@Test
	@DisplayName("OrderId 는 final 이다 — 하위 타입 금지")
	void final_선언() {
		assertThat(Modifier.isFinal(OrderId.class.getModifiers())).isTrue();
	}

	@Test
	@DisplayName("같은 원문이면 같은 값이다")
	void 값_동등성() {
		assertThat(new OrderId(RAW)).isEqualTo(new OrderId(RAW));
		assertThat(new OrderId(RAW)).hasSameHashCodeAs(new OrderId(RAW));
		assertThat(new OrderId(RAW)).isNotEqualTo(new OrderId("other-order"));
	}

	@Test
	@DisplayName("원문은 raw() 로만 꺼낸다 — 토스 호출·DB 저장 전용")
	void 원문_접근자() {
		assertThat(new OrderId(RAW).raw()).isEqualTo(RAW);
	}

	/**
	 * 🔴 U14 의 구조적 방어. 마스킹을 호출자의 선택에 맡기면 한 번만 잊어도 원문이 로그로 샌다.
	 * 이 단언이 깨지면 "주문 식별자를 아는 것 = 미지급 주문을 가로챌 수 있는 것"이 현실이 된다.
	 */
	@Test
	@DisplayName("toString 은 마스킹된 값이다 — 원문이 로그·예외 메시지로 새지 않는다")
	void toString_은_마스킹이다() {
		OrderId orderId = new OrderId(RAW);

		assertThat(orderId.toString()).isEqualTo("13c9***");
		assertThat(orderId.toString()).doesNotContain(RAW);
		// 문자열 연결·로그 포맷도 같은 경로를 탄다
		assertThat("주문=" + orderId).doesNotContain(RAW);
	}

	@Test
	@DisplayName("앞 4자만 남긴다")
	void 마스킹_폭() {
		assertThat(new OrderId("abcdefgh").masked()).isEqualTo("abcd***");
	}

	/** 짧은 입력에서 원문이 통째로 드러나면 안 된다 — 어떤 입력에서도 새지 않아야 한다. */
	@ParameterizedTest(name = "\"{0}\" → 전부 가린다")
	@ValueSource(strings = {"a", "ab", "abc"})
	@DisplayName("4자 미만은 전부 가린다")
	void 짧은_값은_전부_가린다(String shortValue) {
		assertThat(new OrderId(shortValue).masked()).isEqualTo("***");
	}

	@ParameterizedTest
	@EmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("빈 주문 식별자는 만들 수 없다")
	void 빈_값_거부(String blank) {
		assertThatThrownBy(() -> new OrderId(blank))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("null 주문 식별자는 만들 수 없다")
	void null_거부() {
		assertThatThrownBy(() -> new OrderId(null))
			.isInstanceOf(NullPointerException.class);
	}
}
