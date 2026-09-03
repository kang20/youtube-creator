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

class AnonymousKeyFormatTest {

	@Nested
	@DisplayName("isValid — U5 형식 사전 검증 (외부 검증이 없으므로 유일한 입력 방어선)")
	class IsValid {

		@ParameterizedTest(name = "[{index}] \"{0}\" → false")
		@NullAndEmptySource
		@ValueSource(strings = {" ", "   ", "\t", "\n"})
		@DisplayName("null·빈 문자열·공백뿐인 값은 형식 위반이다")
		void 빈_값은_거부한다(String raw) {
			assertThat(AnonymousKeyFormat.isValid(raw)).isFalse();
		}

		@Test
		@DisplayName("공백이 아니고 길이 상한 안이면 통과한다")
		void 정상_값은_통과한다() {
			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.VALID)).isTrue();
			assertThat(AnonymousKeyFormat.isValid("a")).isTrue();
		}

		@Test
		@DisplayName("길이 상한 경계 — MAX_LENGTH 는 통과, MAX_LENGTH + 1 은 거부")
		void 길이_상한_경계() {
			assertThat(AnonymousKeyFixture.atMaxLength()).hasSize(AnonymousKeyFormat.MAX_LENGTH);
			assertThat(AnonymousKeyFixture.tooLong()).hasSize(AnonymousKeyFormat.MAX_LENGTH + 1);

			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.atMaxLength())).isTrue();
			assertThat(AnonymousKeyFormat.isValid(AnonymousKeyFixture.tooLong())).isFalse();
		}

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

		@Test
		@DisplayName("앞 4자만 남기고 나머지를 가린다 — 원문이 남지 않는다")
		void 앞_4자만_남긴다() {
			String raw = AnonymousKeyFixture.VALID;

			String masked = AnonymousKeyFormat.mask(raw);

			assertThat(masked).isEqualTo(raw.substring(0, 4) + "***");
			assertThat(masked).doesNotContain(raw);
			assertThat(raw).contains(masked.substring(0, 4));
		}

		@ParameterizedTest(name = "[{index}] \"{0}\" → \"***\"")
		@NullAndEmptySource
		@ValueSource(strings = {"a", "ab", "abc"})
		@DisplayName("4자 미만·null 은 원문을 전혀 남기지 않는다")
		void 짧은_값은_전부_가린다(String raw) {
			assertThat(AnonymousKeyFormat.mask(raw)).isEqualTo("***");
		}

		@Test
		@DisplayName("정확히 4자는 앞 4자 규칙을 그대로 적용한다")
		void 정확히_4자_경계() {
			assertThat(AnonymousKeyFormat.mask("abcd")).isEqualTo("abcd***");
		}
	}

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
