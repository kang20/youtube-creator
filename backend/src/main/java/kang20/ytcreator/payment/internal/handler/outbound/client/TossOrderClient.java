package kang20.ytcreator.payment.internal.handler.outbound.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import kang20.ytcreator.payment.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TossOrderClient {

	private static final Logger log = LoggerFactory.getLogger(TossOrderClient.class);

	static final String ORDER_STATUS_PATH = "/api-partner/v1/apps-in-toss/order/get-order-status";

	private static final String RESULT_SUCCESS = "SUCCESS";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	@Autowired
	TossOrderClient(
			@Value("${ytcreator.payment.toss.enabled:false}") boolean enabled,
			@Value("${ytcreator.payment.toss.base-url:https://apps-in-toss-api.toss.im}") String baseUrl,
			@Value("${ytcreator.payment.toss.keystore-path:}") String keystorePath,
			@Value("${ytcreator.payment.toss.keystore-password:}") String keystorePassword) {
		this.restClient = enabled ? buildMutualTlsClient(baseUrl, keystorePath, keystorePassword) : null;
	}

	TossOrderClient(RestClient restClient) {
		this.restClient = restClient;
	}

	public TossOrderStatus statusOf(OrderId orderId) {
		if (restClient == null) {
			log.warn("[toss] 클라이언트 비활성(enabled=false) — 주문 조회 불가");
			return TossOrderStatus.unavailable();
		}

		try {
			Envelope envelope = restClient.post()
				.uri(ORDER_STATUS_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new OrderStatusRequest(orderId.raw()))
				.retrieve()
				.body(Envelope.class);

			if (envelope == null || !RESULT_SUCCESS.equals(envelope.resultType()) || envelope.success() == null) {
				log.warn("[toss] 조회 실패 — resultType={}, orderId={}",
					envelope == null ? null : envelope.resultType(), orderId);
				return TossOrderStatus.unavailable();
			}

			return TossOrderStatus.of(envelope.success().status(), envelope.success().sku());
		} catch (RestClientException e) {
			log.warn("[toss] 호출 실패({}) — orderId={}", e.getClass().getSimpleName(), orderId);
			return TossOrderStatus.unavailable();
		}
	}

	private static RestClient buildMutualTlsClient(String baseUrl, String keystorePath, String keystorePassword) {
		if (!StringUtils.hasText(keystorePath) || !StringUtils.hasText(keystorePassword)) {
			// 활성화했으면 인증서는 필수 — 조용히 뜨면 지급이 전부 실패로 죽는다
			throw new IllegalStateException(
				"토스 mTLS 가 활성화됐는데 인증서 설정이 비어 있다 — 기동을 중단한다(fail-fast)");
		}

		try {
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			try (InputStream in = Files.newInputStream(Path.of(keystorePath))) {
				keyStore.load(in, keystorePassword.toCharArray());
			}

			KeyManagerFactory keyManagerFactory =
				KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagerFactory.init(keyStore, keystorePassword.toCharArray());

			SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

			HttpClient httpClient = HttpClient.newBuilder()
				.sslContext(sslContext)
				.connectTimeout(CONNECT_TIMEOUT)
				.build();

			JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
			requestFactory.setReadTimeout(READ_TIMEOUT);

			return RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build();
		} catch (GeneralSecurityException | IOException e) {
			// 인증서 경로·비밀번호를 메시지에 싣지 않는다
			throw new IllegalStateException("토스 mTLS 클라이언트 조립 실패 — 인증서 설정을 확인하라", e);
		}
	}

	record OrderStatusRequest(String orderId) {
	}

	record Envelope(String resultType, Success success) {

		record Success(String orderId, String status, String reason, String sku, String statusDeterminedAt) {
		}
	}
}
