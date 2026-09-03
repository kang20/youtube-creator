package kang20.ytcreator.auth.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import kang20.ytcreator.shared.security.AnonymousKeyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class AnonymousKeyHasherTest {

	private final AnonymousKeyHasher hasher = new AnonymousKeyHasher();

	@Test
	@DisplayName("같은 익명키는 항상 같은 해시가 된다 — 솔트도 랜덤도 섞이지 않는다")
	void 결정적이다() {
		String key = AnonymousKeyFixture.VALID;

		List<String> hashes = IntStream.range(0, 20).mapToObj(i -> hasher.hash(key)).toList();

		assertThat(hashes).containsOnly(hashes.getFirst());
		assertThat(new AnonymousKeyHasher().hash(key))
			.as("인스턴스가 달라도 같아야 한다 — 상태를 갖지 않는다")
			.isEqualTo(hashes.getFirst());
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@ValueSource(strings = {"a", "toss-anon-hash-0123456789abcdef", "한글 익명키", "", "!@#$%^&*()"})
	@DisplayName("출력은 입력 길이와 무관하게 항상 소문자 hex 64자다")
	void 출력은_소문자_hex_64자다(String raw) {
		String hash = hasher.hash(raw);

		assertThat(hash)
			.hasSize(64)
			.matches("[0-9a-f]{64}")
			.isLowerCase();
	}

	@Test
	@DisplayName("입력이 상한 길이여도 출력 길이는 그대로 64다")
	void 상한_길이_입력도_64자로_줄어든다() {
		assertThat(hasher.hash(AnonymousKeyFixture.atMaxLength())).hasSize(64);
	}

	@Test
	@DisplayName("서로 다른 익명키는 서로 다른 해시가 된다")
	void 서로_다른_입력은_충돌하지_않는다() {
		Set<String> hashes = new HashSet<>();
		int count = 500;

		for (int i = 0; i < count; i++) {
			hashes.add(hasher.hash(AnonymousKeyFixture.unique("collision")));
		}

		assertThat(hashes).hasSize(count);

		// 한 글자만 달라도 완전히 다른 값이어야 한다(눈사태 효과).
		assertThat(hasher.hash("anon-key-a")).isNotEqualTo(hasher.hash("anon-key-b"));
		assertThat(hasher.hash("anon-key")).isNotEqualTo(hasher.hash("anon-key "));
	}

	@Test
	@DisplayName("SHA-256 표준 테스트 벡터와 일치한다 — 알고리즘을 바꾸면 기존 행을 전부 못 찾는다")
	void SHA_256_표준_벡터와_일치한다() {
		assertThat(hasher.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(hasher.hash(""))
			.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}

	@Test
	@DisplayName("개발자 실측 참조값과 일치한다")
	void 개발자_실측_참조값과_일치한다() {
		assertThat(hasher.hash("toss-anon-PROBE-RAW-KEY-9f2a-DO-NOT-LOG"))
			.isEqualTo("88cc1596cb8339a1b5cac49ab90b30442065ddc9f3fd2603547b1ac9ace755ba");
	}

	@Test
	@DisplayName("입력을 UTF-8 로 읽는다 — 플랫폼 기본 인코딩에 흔들리지 않는다")
	void UTF_8_로_읽는다() {
		String raw = "익명키-é-🙂";

		assertThat(hasher.hash(raw))
			.isEqualTo(hasher.hash(new String(raw.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
		// UTF-8 로 읽지 않으면 아래 두 값이 같아질 수 없다(다른 바이트열이 되므로).
		assertThat(hasher.hash("é")).isNotEqualTo(hasher.hash("e"));
	}

	@Test
	@DisplayName("해시 결과에 익명키 원문이 들어 있지 않다")
	void 해시에_원문이_없다() {
		String raw = "toss-anon-SECRET-MARKER-0123456789";

		String hash = hasher.hash(raw);

		assertThat(hash)
			.doesNotContain(raw)
			.doesNotContain("SECRET")
			.doesNotContain("toss");
	}

	@Test
	@DisplayName("SHA-256 을 못 찾으면 감싸서 던지되, 예외 메시지에 익명키 원문을 넣지 않는다")
	void 알고리즘이_없으면_원문_없이_실패한다() {
		String raw = "toss-anon-SECRET-MARKER-0123456789";

		try (MockedStatic<MessageDigest> messageDigest = Mockito.mockStatic(MessageDigest.class)) {
			messageDigest.when(() -> MessageDigest.getInstance("SHA-256"))
				.thenThrow(new NoSuchAlgorithmException("강제 주입 — 정상 JVM 에서는 일어나지 않는다"));

			assertThatThrownBy(() -> hasher.hash(raw))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SHA-256")
				.hasMessageNotContaining(raw)
				.hasMessageNotContaining("SECRET")
				.hasCauseInstanceOf(NoSuchAlgorithmException.class);
		}
	}
}
