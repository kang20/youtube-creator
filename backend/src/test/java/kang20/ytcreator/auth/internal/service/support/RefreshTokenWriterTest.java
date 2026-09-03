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

class RefreshTokenWriterTest {

	private final RefreshTokenWriter writer =
		new RefreshTokenWriter(Mockito.mock(RefreshTokenRepository.class));

	@Test
	@DisplayName("해시는 SHA-256 소문자 hex 64자이고 표준 벡터와 일치한다")
	void 해시_값_계약() {
		assertThat(writer.hash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
			.hasSize(64)
			.matches("[0-9a-f]{64}");
		assertThat(writer.hash("abc")).isEqualTo(writer.hash("abc"));
	}

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
