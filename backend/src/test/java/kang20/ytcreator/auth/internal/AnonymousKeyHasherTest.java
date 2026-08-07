package kang20.ytcreator.auth.internal;

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

/**
 * auth-design.md §3-2 (v2, blockers B4) — 익명키를 저장·조회용 해시로 바꾸는 지점.
 *
 * <p>이 클래스가 깨지면 <b>기존 사용자를 전원 못 찾는다.</b> 해시가 조회 키이므로(§5-1 ⓪)
 * 알고리즘·인코딩·대소문자가 한 글자라도 바뀌면 같은 익명키가 다른 행을 가리키고,
 * 모든 사용자가 신규 사용자로 다시 등록된다 — 구독이 끊기는 것과 같은 결과다(auth.md §4-6 참조).
 * 그래서 여기서는 "동작한다"가 아니라 <b>"값이 이 값이다"</b> 를 고정한다.
 */
class AnonymousKeyHasherTest {

	private final AnonymousKeyHasher hasher = new AnonymousKeyHasher();

	/**
	 * §3-2 — 조회 키이므로 <b>결정적</b>이어야 한다(솔트 없음).
	 * 솔트를 넣거나 랜덤이 섞이면 UNIQUE 제약도 멱등(U2)도 성립하지 않는다.
	 */
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

	/** §3-2 · §3 — 컬럼이 길이 64 고정이므로 출력도 소문자 hex 64자여야 한다. */
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

	/** 상한 길이 원문도 같은 64자로 줄어든다 — §12-2 가 해소된 이유가 이것이다. */
	@Test
	@DisplayName("입력이 상한 길이여도 출력 길이는 그대로 64다")
	void 상한_길이_입력도_64자로_줄어든다() {
		assertThat(hasher.hash(AnonymousKeyFixture.atMaxLength())).hasSize(64);
	}

	/**
	 * §3-2 — 서로 다른 익명키는 서로 다른 사용자여야 한다(U2 "익명키당 하나").
	 * 충돌하면 <b>남의 계정으로 들어간다.</b>
	 */
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

	/**
	 * ⚠️ <b>알고리즘 고정.</b> NIST 표준 SHA-256 테스트 벡터로 "정말 SHA-256 인가"를 못 박는다.
	 * 누가 알고리즘이나 hex 대소문자를 바꾸면 여기서 먼저 걸린다 —
	 * 실제 DB 에 행이 쌓인 뒤 바뀌면 전 사용자를 잃는다.
	 */
	@Test
	@DisplayName("SHA-256 표준 테스트 벡터와 일치한다 — 알고리즘을 바꾸면 기존 행을 전부 못 찾는다")
	void SHA_256_표준_벡터와_일치한다() {
		assertThat(hasher.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(hasher.hash(""))
			.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
	}

	/**
	 * round-3-dev.md 가 실측 프로브에서 쓴 참조값. 개발자 실측과 테스트가 <b>같은 계약</b>을 보고 있는지
	 * 확인한다 — 어긋나면 그 보고서의 "원문 0회 / 해시 16회" 실측을 신뢰할 수 없다.
	 */
	@Test
	@DisplayName("개발자 실측 참조값과 일치한다")
	void 개발자_실측_참조값과_일치한다() {
		assertThat(hasher.hash("toss-anon-PROBE-RAW-KEY-9f2a-DO-NOT-LOG"))
			.isEqualTo("88cc1596cb8339a1b5cac49ab90b30442065ddc9f3fd2603547b1ac9ace755ba");
	}

	/** 인코딩 고정 — UTF-8 이 아닌 것으로 바뀌면 비ASCII 익명키의 해시가 조용히 달라진다. */
	@Test
	@DisplayName("입력을 UTF-8 로 읽는다 — 플랫폼 기본 인코딩에 흔들리지 않는다")
	void UTF_8_로_읽는다() {
		String raw = "익명키-é-🙂";

		assertThat(hasher.hash(raw))
			.isEqualTo(hasher.hash(new String(raw.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
		// UTF-8 로 읽지 않으면 아래 두 값이 같아질 수 없다(다른 바이트열이 되므로).
		assertThat(hasher.hash("é")).isNotEqualTo(hasher.hash("e"));
	}

	/**
	 * U6 · auth.md §4-5 — 해시에는 원문의 흔적이 없다. 이것이 §3-2 가 성립하는 전제다
	 * (제약 위반 메시지에 해시가 실려도 원문이 새지 않는 이유).
	 */
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

	/**
	 * U6 · auth.md §4-5 — <b>"예외 메시지"에도 원문을 남기지 않는다.</b>
	 *
	 * <p>{@code MessageDigest.getInstance("SHA-256")} 은 JDK 명세상 반드시 성공하므로 정상 경로로는
	 * 이 분기에 닿을 수 없다(체크 예외라 문법상 생략만 못 할 뿐이다). 그래서 정적 모킹으로 강제로 열었다.
	 *
	 * <p>⚠️ <b>커버리지를 채우려고 호출만 하는 테스트가 아니다.</b> 여기서 보는 것은 U6 다 —
	 * 실패 경로에서 흔히 하는 실수가 "무엇을 해싱하다 실패했는지" 를 메시지에 넣는 것이고,
	 * 그 순간 익명키 원문이 ERROR 스택트레이스를 타고 로그로 나간다(그것이 blockers B4 의 원형이었다).
	 */
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
