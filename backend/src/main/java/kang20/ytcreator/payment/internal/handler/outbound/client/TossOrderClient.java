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

/**
 * 토스 {@code get-order-status} 호출 — <b>토스를 부르는 유일한 지점</b>이다.
 * IAP 서버 API 는 이것 하나뿐이고 인증은 <b>mTLS 단독</b>이다
 * (new-domain/payment.md 참고자료 ① — OpenAPI 원문의 헤더 파라미터가 비어 있다).
 *
 * <p>⚠️ <b>트랜잭션 밖에서만 호출된다.</b> 안에서 부르면 DB 커넥션을 물고 네트워크를 기다려
 * 30초 예산과 커넥션 풀이 동시에 무너진다.
 *
 * <p><b>조립 게이트</b>: {@code enabled=false} 면 조립을 생략하고 모든 조회를 실패로 답한다
 * (local·test·인증서 준비 전 운영 기동 허용). {@code enabled=true} 인데 인증서 설정이 비면
 * <b>기동 실패</b>다 — 조용히 뜨면 지급이 전부 실패로 죽는다.
 *
 * <p>⚠️ 인터페이스로 두지 않는다 — 구현체가 하나뿐인데 인터페이스는 과한 추상화다.
 * 테스트는 아래 패키지-프라이빗 생성자로 목 서버에 바인딩한다.
 *
 * <p>⚠️ <b>로그에 주문 식별자 원문을 싣지 않는다</b> — {@link OrderId#toString()} 이 마스킹을 강제한다.
 */
@Component
public class TossOrderClient {

	private static final Logger log = LoggerFactory.getLogger(TossOrderClient.class);

	static final String ORDER_STATUS_PATH = "/api-partner/v1/apps-in-toss/order/get-order-status";

	private static final String RESULT_SUCCESS = "SUCCESS";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/** 권장 타임아웃이 문서에 없다 — 30초 예산 안에서 보수적으로 잡는다. */
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

	/** 테스트 시임 — mTLS 조립을 우회한다. 조립 자체는 테스트 대상이 아니다. */
	TossOrderClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * 주문 상태를 조회한다. 봉투 실패·전송 실패·타임아웃·비활성은 전부
	 * {@link TossOrderStatus#unavailable()} 로 접는다 — 판정은 호출자 몫이다.
	 */
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

	/** 응답 봉투. 실패 봉투의 {@code error} 상세는 쓰지 않는다 — 전부 같은 실패로 접는다. */
	record Envelope(String resultType, Success success) {

		record Success(String orderId, String status, String reason, String sku, String statusDeterminedAt) {
		}
	}
}
