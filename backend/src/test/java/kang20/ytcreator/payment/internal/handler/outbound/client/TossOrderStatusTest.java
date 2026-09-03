package kang20.ytcreator.payment.internal.handler.outbound.client;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TossOrderStatusTest {

	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	@Test
	@DisplayName("상태가 없으면 ERROR 로 접는다 — 상태를 모르면 지급 대상이 아니다")
	void 상태_없음() {
		TossOrderStatus status = TossOrderStatus.of(null, SKU);

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(OrderStatus.ERROR);
		assertThat(status.grantable()).isFalse();
	}

	@Test
	@DisplayName("미문서화 상태 문자열은 ERROR 로 접는다")
	void 미문서화_상태() {
		assertThat(TossOrderStatus.of("BRAND_NEW_STATUS", SKU).status()).isEqualTo(OrderStatus.ERROR);
	}

	@Test
	@DisplayName("확인 실패는 상태도 상품 코드도 없고 지급 대상이 아니다")
	void 확인_실패() {
		TossOrderStatus status = TossOrderStatus.unavailable();

		assertThat(status.available()).isFalse();
		assertThat(status.status()).isNull();
		assertThat(status.sku()).isNull();
	}

	@ParameterizedTest(name = "{0} → grantable={1}")
	@CsvSource({
		"PURCHASED,         true",
		"PAYMENT_COMPLETED, true",
		"ORDER_IN_PROGRESS, false",
		"FAILED,            false",
		"REFUNDED,          false",
		"NOT_FOUND,         false",
		"MINIAPP_MISMATCH,  false",
		"ERROR,             false"
	})
	@DisplayName("지급 대상은 PURCHASED·PAYMENT_COMPLETED 둘뿐이다")
	void 지급_대상_판정(OrderStatus status, boolean grantable) {
		assertThat(TossOrderStatus.of(status.name(), SKU).grantable()).isEqualTo(grantable);
	}
}
