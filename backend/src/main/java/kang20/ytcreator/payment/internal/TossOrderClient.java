package kang20.ytcreator.payment.internal;

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
 * 토스 {@code get-order-status} 호출 — <b>토스를 부르는 유일한 지점</b>이다(✅-11 격리 · §12-2).
 * IAP 서버 API 는 이것 하나뿐이고 인증은 mTLS 단독이다(iap-essentials §3-3).
 *
 * <p>⚠️ <b>트랜잭션 밖에서만 호출된다</b>(§2-1 쟁점 2) — 트랜잭션 안에서 부르면 DB 커넥션을
 * 물고 네트워크를 기다려 30초 예산과 커넥션 풀이 동시에 무너진다(§6-2 함정 ④).
 *
 * <p>조립은 {@code RestClient} + JDK {@code HttpClient}(SSLContext 주입) — docs/rule/toss-integration.md.
 * Boot 4 는 {@code RestClient.Builder} 자동구성이 별도 모듈이라 빌더 빈을 주입받지 않고 직접 만든다.
 * <b>조립 게이트</b>: {@code enabled=false} 면 조립을 생략하고 모든 조회를 실패(=PAY_006 경로)로
 * 답한다(local/test·인증서 준비 전 운영 기동 허용). {@code enabled=true} 인데 인증서 설정이 비면
 * <b>기동 실패(fail-fast)</b>다.
 *
 * <p>⚠️ 인터페이스로 두지 않는다 — 구현체가 하나뿐인데 인터페이스는 과한 추상화다(§12-2).
 * U14: {@code orderId} 는 로그에도 앞 4자만 남긴다.
 */
@Component
public class TossOrderClient {

	private static final Logger log = LoggerFactory.getLogger(TossOrderClient.class);

	static final String ORDER_STATUS_PATH = "/api-partner/v1/apps-in-toss/order/get-order-status";

	private static final String RESULT_SUCCESS = "SUCCESS";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

	/** 권장 타임아웃이 문서에 없다 — 30초 예산(payment.md §4-6) 안에서 보수적으로 잡는다. */
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

	/** 테스트 시임 — mTLS 조립을 우회해 목 서버 바인딩 클라이언트를 받는다(§10 — 조립 자체는 테스트 대상이 아니다). */
	TossOrderClient(RestClient restClient) {
		this.restClient = restClient;
	}

	/**
	 * 주문 상태 조회(§5-2②). 봉투 실패·전송 실패·타임아웃·비활성은 전부
	 * {@link TossOrderStatus#unavailable()} 로 접는다 — 판정(PAY_006)은 호출자 몫이다.
	 */
	public TossOrderStatus statusOf(String orderId) {
		if (restClient == null) {
			log.warn("[toss] 클라이언트 비활성(enabled=false) — 주문 조회 불가");
			return TossOrderStatus.unavailable();
		}

		try {
			Envelope envelope = restClient.post()
				.uri(ORDER_STATUS_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(new OrderStatusRequest(orderId))
				.retrieve()
				.body(Envelope.class);

			if (envelope == null || !RESULT_SUCCESS.equals(envelope.resultType()) || envelope.success() == null) {
				log.warn("[toss] 조회 실패 — resultType={}, orderId={}",
					envelope == null ? null : envelope.resultType(), OrderIdMask.mask(orderId));
				return TossOrderStatus.unavailable();
			}

			return TossOrderStatus.of(envelope.success().status(), envelope.success().sku());
		} catch (RestClientException e) {
			log.warn("[toss] 호출 실패({}) — orderId={}",
				e.getClass().getSimpleName(), OrderIdMask.mask(orderId));
			return TossOrderStatus.unavailable();
		}
	}

	private static RestClient buildMutualTlsClient(String baseUrl, String keystorePath, String keystorePassword) {
		if (!StringUtils.hasText(keystorePath) || !StringUtils.hasText(keystorePassword)) {
			// 활성화했으면 인증서는 필수 — 조용히 뜨면 지급이 전부 PAY_006 으로 죽는다(toss-integration.md)
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
			// 인증서 경로·비밀번호를 메시지에 싣지 않는다(toss-integration.md)
			throw new IllegalStateException("토스 mTLS 클라이언트 조립 실패 — 인증서 설정을 확인하라", e);
		}
	}

	record OrderStatusRequest(String orderId) {
	}

	/** 응답 봉투(iap-essentials §3-3). 실패 봉투의 {@code error} 상세는 쓰지 않는다 — 전부 unavailable 이다. */
	record Envelope(String resultType, Success success) {

		record Success(String orderId, String status, String reason, String sku, String statusDeterminedAt) {
		}
	}
}
