package kang20.ytcreator.auth.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import kang20.ytcreator.auth.internal.handler.outbound.repository.RefreshTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * U10 — refresh 해시 계산의 <b>값 계약</b> 단위 테스트. 회전·폐기의 동작 검증은
 * {@code RefreshRotationTest}(실 DB)가 하고, 여기는 해시 축만 본다 —
 * {@code AnonymousKeyHasherTest} 와 같은 이유·같은 방식이다(round-1-dev.md 판단 5:
 * support 는 support 를 못 부르므로 같은 계산이 여기 <b>한 벌 더</b> 존재한다. 계약이 어긋나면 안 된다).
 */
class RefreshTokenWriterTest {

	private final RefreshTokenWriter writer =
		new RefreshTokenWriter(Mockito.mock(RefreshTokenRepository.class));

	/**
	 * U10 · §14-3 — 해시는 <b>조회 키</b>라 결정적이어야 하고(SHA-256 소문자 hex 64자),
	 * 알고리즘·인코딩이 바뀌면 저장된 refresh 를 전부 못 찾아 전 사용자 세션이 끊긴다.
	 * NIST 표준 벡터로 "정말 SHA-256 인가"를 못 박는다({@code AnonymousKeyHasherTest} 선례).
	 */
	@Test
	@DisplayName("해시는 SHA-256 소문자 hex 64자이고 표준 벡터와 일치한다")
	void 해시_값_계약() {
		assertThat(writer.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
			.hasSize(64)
			.matches("[0-9a-f]{64}");
		assertThat(writer.hash("abc")).isEqualTo(writer.hash("abc"));
	}

	/**
	 * U10(U6 와 같은 규율) — <b>실패 경로의 예외 메시지에도 refresh 원문을 넣지 않는다.</b>
	 *
	 * <p>{@code MessageDigest.getInstance("SHA-256")} 은 JDK 명세상 반드시 성공하므로 정상 경로로는
	 * 이 분기에 닿을 수 없다 — 정적 모킹으로 강제로 연다({@code AnonymousKeyHasherTest} 와 동일 기법).
	 * 커버리지용이 아니라, 실패 메시지에 "무엇을 해싱하다 실패했는지"를 넣는 흔한 실수(blockers B4 의
	 * 원형)가 들어오면 여기서 빨개진다.
	 */
	@Test
	@DisplayName("SHA-256 을 못 찾으면 감싸서 던지되, 예외 메시지에 refresh 원문을 넣지 않는다")
	void 알고리즘이_없으면_원문_없이_실패한다() {
		String raw = "refresh-SECRET-MARKER-0123456789";

		try (MockedStatic<MessageDigest> messageDigest = Mockito.mockStatic(MessageDigest.class)) {
			messageDigest.when(() -> MessageDigest.getInstance("SHA-256"))
				.thenThrow(new NoSuchAlgorithmException("강제 주입 — 정상 JVM 에서는 일어나지 않는다"));

			assertThatThrownBy(() -> writer.hash(raw))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SHA-256")
				.hasMessageNotContaining(raw)
				.hasMessageNotContaining("SECRET")
				.hasCauseInstanceOf(NoSuchAlgorithmException.class);
		}
	}
}
