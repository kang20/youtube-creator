package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import kang20.ytcreator.base.MutableClock;
import kang20.ytcreator.config.JpaAuditingConfig;
import kang20.ytcreator.payment.dto.ProductCatalog;
import kang20.ytcreator.payment.internal.client.TossOrderClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * U1 · payment.md §5-2 "(키가 null) 미노출 상품" — <b>sku 를 설정하지 않은</b> 컨텍스트에서
 * 상품이 null 로 내려가고 프론트가 결제 버튼을 숨길 수 있는지(K3).
 *
 * <p>별도 클래스인 이유: 다른 모듈 테스트는 전부 sku 를 설정하므로 "미설정" 부팅은 여기서만 재현된다.
 */
@ActiveProfiles("test")
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Import({JpaAuditingConfig.class, PaymentProductExposureTest.FixedClockConfig.class})
class PaymentProductExposureTest {

	@TestConfiguration
	static class FixedClockConfig {
		@Bean
		Clock clock() {
			return new MutableClock(PaymentFixture.BASE_TIME);
		}
	}

	@Autowired
	private PaymentService paymentService;

	@MockitoBean
	private TossOrderClient tossOrderClient;

	/** payment.md §5-2 — 설정이 비면 <b>키 전체가 null</b>(미노출). 부분 노출로 유령 상품을 그리지 않는다 */
	@Test
	@DisplayName("sku 미설정이면 상품 키 전체가 null(미노출)이다")
	void 미설정은_미노출() {
		ProductCatalog catalog = paymentService.products();

		assertThat(catalog.oneTime()).isNull();
		assertThat(catalog.subscription())
			.as("§5-2 '(키가 null) 미노출' — sku 없이 renewalCycle 만 내려가면 프론트가 유령 상품을 그린다")
			.isNull();
	}
}
