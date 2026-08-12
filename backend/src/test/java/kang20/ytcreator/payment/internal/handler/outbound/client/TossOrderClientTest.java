package kang20.ytcreator.payment.internal.handler.outbound.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 토스 {@code get-order-status} 호출·응답 매핑 단위
 * (payment-design.md §10 {@code TossOrderClientTest} — MockRestServiceServer).
 *
 * <p>덮는 것: {@code resultType} 성공/실패 분류 · <b>비즈니스 오류가 HTTP 200 으로 오는 경우</b> ·
 * {@code success.sku} 부재 방어 · 전송 실패(타임아웃) → PAY_006 경로 · 조립 게이트
 * ({@code enabled=false} 우회 · fail-fast) · {@code TossOrderStatus} 의 status 8종 × 판정 매핑 전수.
 *
 * <p>⚠️ 실 mTLS 호출은 여기 없다 — ✅-11(로그인 없이 호출 가능한가)은 인증서 발급 후
 * 실호출로만 판명된다(§10 수용한 검증 한계 3).
 */
class TossOrderClientTest {

	private static final String BASE_URL = "https://toss.test";

	private MockRestServiceServer server;
	private TossOrderClient client;

	private void setUpMockServer() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		this.client = new TossOrderClient(builder.build());
	}

	private void expectOrderStatusCall(String responseBody) {
		server.expect(requestTo(BASE_URL + TossOrderClient.ORDER_STATUS_PATH))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.orderId").value("order-probe"))
			.andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
	}

	// ── 호출 경로 (§5-6 응답 봉투) ──────────────────────────────────────

	/** U2 — 봉투 SUCCESS + 주문 status·sku 를 그대로 우리 표현으로 옮긴다 */
	@Test
	@DisplayName("SUCCESS 봉투는 status·sku 가 담긴 응답이 된다")
	void 성공_봉투() {
		setUpMockServer();
		expectOrderStatusCall("""
			{ "resultType": "SUCCESS",
			  "success": { "orderId": "order-probe", "status": "PURCHASED", "reason": "구매 완료",
			               "sku": "sku-one-time", "statusDeterminedAt": "2026-08-11T12:00:00" } }
			""");

		TossOrderStatus status = client.statusOf("order-probe");

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(TossOrderStatus.OrderStatus.PURCHASED);
		assertThat(status.sku()).isEqualTo("sku-one-time");
		server.verify();
	}

	/**
	 * payment.md §5-6 — ⚠️ <b>비즈니스 오류가 HTTP 200 으로 온다.</b> HTTP 상태만 보고 성공
	 * 판정하면 안 된다. {@code resultType != SUCCESS} 는 전부 실패(→ PAY_006 경로)다.
	 */
	@Test
	@DisplayName("HTTP 200 이어도 resultType 이 SUCCESS 가 아니면 실패다")
	void 이백으로_오는_비즈니스_오류() {
		for (String failure : new String[] {
				"FAIL", "HTTP_TIMEOUT", "NETWORK_ERROR", "EXECUTION_FAIL", "INTERRUPTED", "INTERNAL_ERROR"}) {
			setUpMockServer();
			expectOrderStatusCall("""
				{ "resultType": "%s", "error": { "errorCode": "UNKNOWN", "reason": "실패" } }
				""".formatted(failure));

			TossOrderStatus status = client.statusOf("order-probe");

			assertThat(status.available()).as("resultType=%s", failure).isFalse();
		}
	}

	/** §5-6 — resultType 은 SUCCESS 인데 success 본문이 없는 기형 응답도 실패로 접는다 */
	@Test
	@DisplayName("SUCCESS 인데 success 본문이 없으면 실패다")
	void 본문_없는_성공_봉투() {
		setUpMockServer();
		expectOrderStatusCall("{ \"resultType\": \"SUCCESS\" }");

		assertThat(client.statusOf("order-probe").available()).isFalse();
	}

	/** 빈 응답(역직렬화 null) 방어 */
	@Test
	@DisplayName("본문이 아예 없으면 실패다")
	void 빈_응답() {
		setUpMockServer();
		server.expect(requestTo(BASE_URL + TossOrderClient.ORDER_STATUS_PATH))
			.andRespond(withSuccess());

		assertThat(client.statusOf("order-probe").available()).isFalse();
	}

	/** §5-6 — sku 는 MINIAPP_MISMATCH·NOT_FOUND·ERROR 에서 안 온다. null 로 통과해야 한다 */
	@Test
	@DisplayName("sku 부재(NOT_FOUND 류)는 null sku 로 통과한다")
	void sku_부재_방어() {
		setUpMockServer();
		expectOrderStatusCall("""
			{ "resultType": "SUCCESS",
			  "success": { "orderId": "order-probe", "status": "NOT_FOUND", "reason": "주문 없음" } }
			""");

		TossOrderStatus status = client.statusOf("order-probe");

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(TossOrderStatus.OrderStatus.NOT_FOUND);
		assertThat(status.sku()).isNull();
	}

	/** R9 — 전송 실패(타임아웃)는 예외가 아니라 실패 응답으로 접는다. 판정(PAY_006)은 호출자 몫 */
	@Test
	@DisplayName("전송 실패·타임아웃은 unavailable 로 접힌다")
	void 전송_실패() {
		setUpMockServer();
		server.expect(requestTo(BASE_URL + TossOrderClient.ORDER_STATUS_PATH))
			.andRespond(withException(new SocketTimeoutException("read timed out")));

		assertThat(client.statusOf("order-probe").available()).isFalse();
	}

	/** HTTP 5xx — retrieve() 가 던지는 RestClientException 도 같은 경로다 */
	@Test
	@DisplayName("HTTP 5xx 도 unavailable 로 접힌다")
	void 서버_오류() {
		setUpMockServer();
		server.expect(requestTo(BASE_URL + TossOrderClient.ORDER_STATUS_PATH))
			.andRespond(withServerError());

		assertThat(client.statusOf("order-probe").available()).isFalse();
	}

	// ── 조립 게이트 (§10 — 조립은 enabled=false 로 우회) ─────────────────

	/** enabled=false — 조립을 생략하고 모든 조회가 실패(=PAY_006 경로). local/test 기동 허용 */
	@Test
	@DisplayName("enabled=false 면 조회는 전부 unavailable 이다")
	void 비활성_게이트() {
		TossOrderClient disabled = new TossOrderClient(false, BASE_URL, "", "");

		assertThat(disabled.statusOf("order-probe").available()).isFalse();
	}

	/** fail-fast — 활성인데 인증서 설정이 비면 기동 실패. 조용히 뜨면 지급이 전부 PAY_006 으로 죽는다 */
	@Test
	@DisplayName("enabled=true 인데 인증서 설정이 비면 기동이 실패한다(fail-fast)")
	void 활성인데_인증서_없음() {
		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, "", ""))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fail-fast");

		// 경로만 있고 비밀번호가 빈 경우도 같은 fail-fast 다
		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, "/tmp/client.p12", ""))
			.isInstanceOf(IllegalStateException.class);
	}

	/** 깨진 keystore — 원인 예외를 안고 기동 실패. 메시지에 경로·비밀번호를 싣지 않는다 */
	@Test
	@DisplayName("keystore 가 깨져 있으면 기동이 실패하고 메시지에 비밀은 없다")
	void 깨진_keystore(@TempDir Path tempDir) throws Exception {
		Path broken = tempDir.resolve("broken.p12");
		Files.write(broken, new byte[] {1, 2, 3});

		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, broken.toString(), "secret-password"))
			.isInstanceOf(IllegalStateException.class)
			.satisfies(thrown -> {
				assertThat(thrown.getMessage()).doesNotContain(broken.toString());
				assertThat(thrown.getMessage()).doesNotContain("secret-password");
			});
	}

	/** 정상 keystore 면 mTLS 클라이언트가 조립된다(실호출은 하지 않는다 — §10 검증 한계 3) */
	@Test
	@DisplayName("정상 keystore 면 조립에 성공한다")
	void 정상_조립(@TempDir Path tempDir) throws Exception {
		Path keystoreFile = tempDir.resolve("client.p12");
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		keyStore.load(null, null);
		try (OutputStream out = Files.newOutputStream(keystoreFile)) {
			keyStore.store(out, "keypass".toCharArray());
		}

		assertThatCode(() -> new TossOrderClient(true, BASE_URL, keystoreFile.toString(), "keypass"))
			.doesNotThrowAnyException();
	}

	// ── TossOrderStatus — status 8종 × 판정 매핑 전수 (§5-2②) ───────────

	@Nested
	@DisplayName("TossOrderStatus 매핑 — payment.md §5-4 표 전수")
	class 상태_매핑 {

		/** ✅-1 — PURCHASED 와 PAYMENT_COMPLETED 둘 다 지급. 문서 근거 없는 확정이라 이 한 곳에 몰려 있다 */
		@Test
		@DisplayName("지급 대상은 PURCHASED 와 PAYMENT_COMPLETED 뿐이다(✅-1)")
		void 지급_대상() {
			assertThat(TossOrderStatus.of("PURCHASED", null).grantable()).isTrue();
			assertThat(TossOrderStatus.of("PAYMENT_COMPLETED", null).grantable()).isTrue();

			for (String other : new String[] {
					"ORDER_IN_PROGRESS", "FAILED", "REFUNDED", "NOT_FOUND", "MINIAPP_MISMATCH", "ERROR"}) {
				assertThat(TossOrderStatus.of(other, null).grantable()).as(other).isFalse();
			}
		}

		/** payment.md §5-4 — 지급 불가 status → 에러 코드 매핑 전수 */
		@Test
		@DisplayName("지급 불가 status 는 §5-4 표의 에러 코드로 매핑된다")
		void 거부_코드_매핑() {
			assertThat(TossOrderStatus.of("ORDER_IN_PROGRESS", null).rejection()).isEqualTo(ErrorCode.PAY_002);
			assertThat(TossOrderStatus.of("FAILED", null).rejection()).isEqualTo(ErrorCode.PAY_003);
			assertThat(TossOrderStatus.of("REFUNDED", null).rejection()).isEqualTo(ErrorCode.PAY_003);
			assertThat(TossOrderStatus.of("NOT_FOUND", null).rejection()).isEqualTo(ErrorCode.PAY_004);
			assertThat(TossOrderStatus.of("MINIAPP_MISMATCH", null).rejection()).isEqualTo(ErrorCode.PAY_004);
			assertThat(TossOrderStatus.of("ERROR", null).rejection()).isEqualTo(ErrorCode.PAY_006);
		}

		/** §5-6 — 문서화되지 않은 status 는 실패(ERROR = PAY_006 경로)로 접는다 */
		@Test
		@DisplayName("미문서화 status·null 은 ERROR 로 접힌다")
		void 미지의_status() {
			assertThat(TossOrderStatus.of("SOMETHING_NEW", null).status())
				.isEqualTo(TossOrderStatus.OrderStatus.ERROR);
			assertThat(TossOrderStatus.of(null, null).status())
				.isEqualTo(TossOrderStatus.OrderStatus.ERROR);
			assertThat(TossOrderStatus.of("SOMETHING_NEW", null).rejection()).isEqualTo(ErrorCode.PAY_006);
		}

		/** 봉투 실패 표현 — available=false, status·sku 없음 */
		@Test
		@DisplayName("unavailable() 은 상태 없는 실패 표현이다")
		void unavailable_표현() {
			TossOrderStatus unavailable = TossOrderStatus.unavailable();

			assertThat(unavailable.available()).isFalse();
			assertThat(unavailable.status()).isNull();
			assertThat(unavailable.sku()).isNull();
		}
	}
}
