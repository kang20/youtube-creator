package kang20.ytcreator.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * U14(주문 식별자 비노출)의 payment 자체 마스킹 정책.
 *
 * <p>근거: payment-design.md §9 · 코드 리뷰 🟡-2({@code AnonymousKeyFormat} 정책 결합 분리).
 * 선례: {@code AnonymousKeyFormatTest}.
 */
class OrderIdMaskTest {

	/** U14 — 정상 orderId(uuid v7)는 앞 4자만 남는다 */
	@Test
	@DisplayName("앞 4자 + \"***\" 만 남는다 — 원문이 로그에 새지 않는다")
	void 앞_4자만_남긴다() {
		assertThat(OrderIdMask.mask("0198c1c2-aaaa-bbbb-cccc-000000000001")).isEqualTo("0198***");
	}

	/** U14 — 4자 미만·null 입력에서도 원문이 새지 않는다 (AnonymousKeyFormat.mask 와 같은 방어) */
	@ParameterizedTest(name = "[{index}] \"{0}\" → \"***\"")
	@NullSource
	@ValueSource(strings = {"", "a", "abc"})
	@DisplayName("4자 미만이면 전부 가린다")
	void 짧은_입력은_전부_가린다(String raw) {
		assertThat(OrderIdMask.mask(raw)).isEqualTo("***");
	}
}
