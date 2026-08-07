package kang20.ytcreator.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * U5(형식 사전 검증) · U6(익명키 비노출)의 순수 함수 부분.
 *
 * <p>근거: auth.md §3 U5·U6 · §4-4(형식 규칙) · §4-5(마스킹 = 앞 4자 + "***") ·
 * auth-design.md §4·§10 {@code AnonymousKeyFormatTest}.
 */
class AnonymousKeyFormatTest {

	@Nested
	@DisplayName("isValid — U5 형식 사전 검증 (외부 검증이 없으므로 유일한 입력 방어선)")
	class IsValid {

		/** U5 — 값이 없으면 형식 위반이다 (auth.md §4-4 "공백 / 빈 값 → 거부") */
		@ParameterizedTest(name = "[{index}] \"{0}\" → false")
		@NullAndEmptySource
		@ValueSource(strings = {" ", "   ", "\t", "\n"})
		@DisplayName("null·빈 문자열·공백뿐인 값은 형식 위반이다")
		void 빈_값은_거부한다(String raw) {
			assertThat(AnonymousKeyFormat.isValid(raw)).isFalse();
		}

		/** U5 — 정상 값은 통과한다. 통과하면 그대로 신뢰한다(auth.md §4-4 "추가 검증은 없다") */
		@Test
		@DisplayName("공백이 아니고 길이 상한 안이면 통과한다")
		void 정상_값은_통과한다() {
			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.VALID)).isTrue();
			assertThat(AnonymousKeyFormat.isValid("a")).isTrue();
		}

		/** U5 — 길이 상한 경계. auth-design.md §12-2 "공백 아님 + 255자 이하" */
		@Test
		@DisplayName("길이 상한 경계 — MAX_LENGTH 는 통과, MAX_LENGTH + 1 은 거부")
		void 길이_상한_경계() {
			assertThat(AnonymousKeyFixture.atMaxLength()).hasSize(AnonymousKeyFormat.MAX_LENGTH);
			assertThat(AnonymousKeyFixture.tooLong()).hasSize(AnonymousKeyFormat.MAX_LENGTH + 1);

			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.atMaxLength())).isTrue();
			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.tooLong())).isFalse();
		}

		/**
		 * auth.md §9-1 🔶-3 은 <b>문자셋 제한을 보류</b>했고 auth-design.md §12-2 는
		 * "문자셋 제한은 넣지 않는다 — 실제 hash 문자셋을 모른 채 좁히면 정상 사용자를 막는다"고 지시한다.
		 * 그 지시가 지켜지는지(=특수문자·유니코드를 임의로 막지 않는지) 회귀로 고정한다.
		 */
		@ParameterizedTest(name = "[{index}] \"{0}\" → true")
		@ValueSource(strings = {"AbC-123_x", "a+b/c=", "한글키", "key.with.dots", "{\"json\":true}"})
		@DisplayName("문자셋은 아직 좁히지 않는다 — 공백/길이 외의 이유로 거부하지 않는다")
		void 문자셋은_제한하지_않는다(String raw) {
			assertThat(AnonymousKeyFormat.isValid(raw)).isTrue();
		}
	}

	@Nested
	@DisplayName("mask — U6 익명키 비노출 (앞 4자 + \"***\")")
	class Mask {

		/** U6 — auth.md §4-5 "추적이 필요할 때는 앞 4자 + *** 형태로만 남긴다" */
		@Test
		@DisplayName("앞 4자만 남기고 나머지를 가린다 — 원문이 남지 않는다")
		void 앞_4자만_남긴다() {
			String raw = AnonymousKeyFixture.VALID;

			String masked = AnonymousKeyFormat.mask(raw);

			assertThat(masked).isEqualTo(raw.substring(0, 4) + "***");
			assertThat(masked).doesNotContain(raw);
			assertThat(raw).contains(masked.substring(0, 4));
		}

		/**
		 * U6 — 4자 미만이면 앞 4자가 곧 원문이라 아무것도 남기지 않는다
		 * (round-1-dev.md "4자 미만이거나 null 이면 \"***\"").
		 */
		@ParameterizedTest(name = "[{index}] \"{0}\" → \"***\"")
		@NullAndEmptySource
		@ValueSource(strings = {"a", "ab", "abc"})
		@DisplayName("4자 미만·null 은 원문을 전혀 남기지 않는다")
		void 짧은_값은_전부_가린다(String raw) {
			assertThat(AnonymousKeyFormat.mask(raw)).isEqualTo("***");
		}

		/** 경계 — 정확히 4자면 앞 4자 규칙이 그대로 적용된다(auth.md §4-5 문구 그대로) */
		@Test
		@DisplayName("정확히 4자는 앞 4자 규칙을 그대로 적용한다")
		void 정확히_4자_경계() {
			assertThat(AnonymousKeyFormat.mask("abcd")).isEqualTo("abcd***");
		}
	}

	/**
	 * 상수 + 순수 함수만 갖는 홀더다(auth-design.md §4 "상태 없음").
	 * 인스턴스를 만들 수 있게 되면 상태를 얹고 싶어지므로 생성자가 private 인지 고정한다.
	 */
	@Test
	@DisplayName("유틸 클래스라 인스턴스화 경로를 열어 두지 않는다")
	void 인스턴스화할_수_없다() throws Exception {
		Constructor<AnonymousKeyFormat> constructor = AnonymousKeyFormat.class.getDeclaredConstructor();

		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		assertThat(Modifier.isFinal(AnonymousKeyFormat.class.getModifiers())).isTrue();

		constructor.setAccessible(true);
		assertThatCode(constructor::newInstance).doesNotThrowAnyException();
	}
}
