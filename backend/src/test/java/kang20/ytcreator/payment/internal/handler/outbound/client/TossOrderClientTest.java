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

class TossOrderClientTest {

	private static final String BASE_URL = "https://toss.test";

	private static final String RAW = "13c9a1ff-2baa-4495-bbfa-a0826ba8c7c0";

	private static final OrderId ORDER = new OrderId(RAW);

	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	private MockRestServiceServer server;

	private TossOrderClient client;

	private void 토스_서버를_세운다() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new TossOrderClient(builder.build());
	}

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

	@Test
	@DisplayName("resultType 이 SUCCESS 가 아니면 HTTP 200 이어도 확인 실패다")
	void 실패_봉투() {
		토스_서버를_세운다();
		응답한다("""
			{"resultType":"FAIL","error":{"reason":"invalid"},"success":null}
			""");

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	@Test
	@DisplayName("SUCCESS 봉투인데 success 본문이 없으면 확인 실패다")
	void 본문_없는_성공_봉투() {
		토스_서버를_세운다();
		응답한다("""
			{"resultType":"SUCCESS","success":null}
			""");

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	@Test
	@DisplayName("응답 본문이 아예 없으면 확인 실패다")
	void 봉투_없음() {
		토스_서버를_세운다();
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH)).andRespond(withNoContent());

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	@Test
	@DisplayName("토스가 5xx 로 답해도 예외가 새지 않고 확인 실패로 접힌다")
	void 전송_실패() {
		토스_서버를_세운다();
		server.expect(requestTo(BASE_URL + ORDER_STATUS_PATH)).andRespond(withServerError());

		assertThat(client.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	@Test
	@DisplayName("비활성(enabled=false)이면 토스를 부르지 않고 확인 실패로 답한다")
	void 비활성() {
		TossOrderClient disabled = new TossOrderClient(false, BASE_URL, "", "");

		assertThat(disabled.statusOf(ORDER)).isEqualTo(TossOrderStatus.unavailable());
	}

	@ParameterizedTest(name = "path=[{0}] password=[{1}]")
	@CsvSource({"'', ''", "'', pw", "/some/path.p12, ''"})
	@DisplayName("활성인데 인증서 설정이 비면 기동을 중단한다 — 조용히 뜨지 않는다")
	void 인증서_설정_누락(String path, String password) {
		assertThatThrownBy(() -> new TossOrderClient(true, BASE_URL, path, password))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("fail-fast");
	}

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
