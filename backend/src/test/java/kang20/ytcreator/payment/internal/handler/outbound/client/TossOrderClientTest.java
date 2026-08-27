package kang20.ytcreator.payment.internal.handler.outbound.client;

import static kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderClient.ORDER_STATUS_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 토스 {@code get-order-status} 호출의 HTTP 계약 — <b>토스를 부르는 유일한 지점</b>
 * (new-domain/payment.md 참고자료 ① · {@link TossOrderClient} javadoc).
 *
 * <p>다른 payment 테스트는 이 클라이언트를 통째로 목으로 막는다(지급 로직이 관심사이므로). 여기서는
 * 반대로 <b>클라이언트 자신</b>이 대상이다 — 목 서버에 바인딩해 ① 봉투 판정 ② 전송 실패 흡수
 * ③ 비활성 ④ mTLS 조립 게이트를 본다. javadoc 이 "테스트는 아래 패키지-프라이빗 생성자로 목 서버에
 * 바인딩한다"고 예고한 시임을 그대로 쓴다.
 *
 * <p>🔴 봉투 실패는 <b>HTTP 200 으로 온다</b>({@link TossOrderStatus} javadoc) — 상태 코드로 성공을
 * 판정하면 안 된다는 계약을 200 응답들로 검증한다.
 */
class TossOrderClientTest {

	private static final String BASE_URL = "https://toss.test";

	/** 원문. 🔴 요청 본문 외 어디에도 나가면 안 되는 값이다(OrderId javadoc). */
	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";

	private static final OrderId ORDER = new OrderId(RAW);

	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	private MockRestServiceServer server;

	private TossOrderClient client;

	/** 목 서버에 바인딩된 클라이언트를 만든다 — mTLS 조립은 우회한다(조립은 아래 별도 테스트가 본다). */
	private void 토스_서버를_세운다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TossOrderClient(builder.build());
	}

	// ── 성공 봉투 ───────────────────────────────────────────────────────

	/**
	 * 정상 봉투({@code resultType=SUCCESS} + {@code success})는 상태·상품 코드를 그대로 옮긴다.
	 * 요청은 문서 경로에 POST/JSON 이고, <b>본문에는 원문 주문 식별자</b>가 실린다 — 원문이 필요한
	 * 두 경로("토스 호출과 DB 저장") 중 하나다(OrderId javadoc).
	 */
	@Test
	@DisplayName("성공 봉투는 상태와 상품 코드를 그대로 옮기고, 요청 본문에는 원문 주문 식별자가 실린다")
	void 성공_봉투() {
		토스_서버를_세운다();
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.orderId").value(RAW))
			.andRespond(withSuccess("""
				{"resultType":"SUCCESS","success":{"orderId":"%s","status":"PURCHASED","reason":null,
				"sku":"%s","statusDeterminedAt":"2026-08-14T12:00:00+09:00"}}
				""".formatted(RAW, SKU), MediaType.APPLICATION_JSON));

		TossOrderStatus status = client.statusOf(ORDER);

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(OrderStatus.PURCHASED);
		assertThat(status.sku()).isEqualTo(SKU);
		server.verify();
	}

	/** 미문서화 상태가 와도 조용히 지급되지 않는다 — {@code ERROR} 로 접는다(TossOrderStatus.parse). */
	@Test
	@DisplayName("모르는 상태 문자열이 와도 ERROR 로 접는다 — 조용히 지급되지 않는다")
	void 미문서화_상태() {
		토스_서버를_세운다();
		응답한다("""
			{"resultType":"SUCCESS","success":{"status":"SOMETHING_NEW","sku":"%s"}}
			""".formatted(SKU));

		TossOrderStatus status = client.statusOf(ORDER);

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(OrderStatus.ERROR);
		assertThat(status.grantable()).isFalse();
	}

	// ── 봉투 실패 (HTTP 200) ────────────────────────────────────────────

	/** 🔴 비즈니스 오류가 200 으로 온다 — {@code resultType != SUCCESS} 는 전부 "확인하지 못했다"다. */
	@Test
	@DisplayName("resultType 이 SUCCESS 가 아니면 HTTP 200 이어도 확인 실패다")
	void 실패_봉투() {
		토스_서버를_세운다();
		응답한다("""
			{"resultType":"FAIL","error":{"reason":"invalid"},"success":null}
			""");

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	/** {@code SUCCESS} 인데 본문이 비어 있는 봉투도 같은 경로다 — 상태를 모르면 지급하지 않는다. */
	@Test
	@DisplayName("SUCCESS 봉투인데 success 본문이 없으면 확인 실패다")
	void 본문_없는_성공_봉투() {
		토스_서버를_세운다();
		응답한다("""
			{"resultType":"SUCCESS","success":null}
			""");

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	/** 봉투 자체가 없는 응답(본문 없음)도 확인 실패다 — 역참조 없이 접는다. */
	@Test
	@DisplayName("응답 본문이 아예 없으면 확인 실패다")
	void 봉투_없음() {
		토스_서버를_세운다();
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH)).andRespond(withNoContent());

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	// ── 전송 실패 ───────────────────────────────────────────────────────

	/**
	 * 전송 실패({@code RestClientException})는 예외로 새어 나가지 않는다 — 지급 경로가 5xx 를 그대로
	 * 던지면 30초 예산 안에서 사용자에게 500 이 나간다. 판정은 호출자 몫이다.
	 */
	@Test
	@DisplayName("토스가 5xx 로 답해도 예외가 새지 않고 확인 실패로 접힌다")
	void 전송_실패() {
		토스_서버를_세운다();
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH)).andRespond(withServerError());

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	// ── 조립 게이트 ─────────────────────────────────────────────────────

	/** {@code enabled=false} 면 조립을 생략하고 모든 조회를 실패로 답한다 — local·test·인증서 준비 전. */
	@Test
	@DisplayName("비활성(enabled=false)이면 토스를 부르지 않고 확인 실패로 답한다")
	void 비활성() {
		TossOrderClient disabled = new TossOrderClient(false, BASE_URL, "", "");

		assertThat(disabled.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	/**
	 * 🔴 {@code enabled=true} 인데 인증서 설정이 비면 <b>기동이 멈춘다</b>. 조용히 뜨면 지급이 전부
	 * 실패로 죽는다(fail-fast).
	 */
	@ParameterizedTest(name = "path=[{0}] password=[{1}]")
	@CsvSource({"'', ''", "'', pw", "/some/path.p12, ''"})
	@DisplayName("활성인데 인증서 설정이 비면 기동을 중단한다 — 조용히 뜨지 않는다")
	void 인증서_설정_누락(String path, String password) {
		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, path, password))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fail-fast");
	}

	/** 인증서를 읽지 못하면 조립 실패로 기동을 멈춘다. ⚠️ 메시지에 경로·비밀번호를 싣지 않는다. */
	@Test
	@DisplayName("인증서 파일을 읽지 못하면 조립 실패로 기동을 중단하고, 메시지에 경로·비밀번호를 싣지 않는다")
	void 인증서_읽기_실패(@TempDir Path tempDir) {
		String missing = tempDir.resolve("없는-인증서.p12").toString();

		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, missing, "secret-password"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("토스 mTLS 클라이언트 조립 실패")
			.hasMessageNotContaining(missing)
			.hasMessageNotContaining("secret-password");
	}

	/**
	 * 정상 PKCS12 면 mTLS 클라이언트가 조립된다 — 조립 경로(키스토어 적재 → KeyManager → SSLContext →
	 * 타임아웃) 전체가 예외 없이 끝나는지만 본다. 실제 토스 호출은 하지 않는다(외부 의존).
	 */
	@Test
	@DisplayName("정상 인증서면 mTLS 클라이언트가 조립된다")
	void mTLS_조립(@TempDir Path tempDir) throws Exception {
		Path keystore = tempDir.resolve("toss.p12");
		String password = "test-keystore-pw";
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		try (OutputStream out = Files.newOutputStream(keystore)) {
			store.store(out, password.toCharArray());
		}

		assertThatCode(() -> new TossOrderClient(true, BASE_URL, keystore.toString(), password))
			.doesNotThrowAnyException();
	}

	private void 응답한다(String body) {
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}
}
