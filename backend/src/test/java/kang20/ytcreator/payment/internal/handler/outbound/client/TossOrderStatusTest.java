package kang20.ytcreator.payment.internal.handler.outbound.client;

import static org.assertj.core.api.Assertions.assertThat;

import kang20.ytcreator.payment.internal.handler.outbound.client.TossOrderStatus.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 토스 응답의 우리 쪽 표현 — 상태 해석과 지급 대상 판정
 * (new-domain/payment.md 참고자료 ① · {@link TossOrderStatus} javadoc).
 *
 * <p>거부 코드 매핑(상태 → PAY_00x)은 지급 흐름에서 {@code PaymentServiceTest#지급_불가} 가 이미
 * 계약으로 본다. 여기서는 <b>해석 자체</b>(미상태·미문서화 값·판정)만 본다.
 */
class TossOrderStatusTest {

	private static final String SKU = "ait.0000010000.af647449.3bd55cfd00.0000000475";

	/**
	 * 상태 없는 성공 봉투도 조용히 지급되지 않는다 — 상태를 모르면 {@code ERROR} 다.
	 * (토스가 {@code success} 는 주면서 {@code status} 를 비운 응답을 방어한다.)
	 */
	@Test
	@DisplayName("상태가 없으면 ERROR 로 접는다 — 상태를 모르면 지급 대상이 아니다")
	void 상태_없음() {
		TossOrderStatus status = TossOrderStatus.of(null, SKU);

		assertThat(status.available()).isTrue();
		assertThat(status.status()).isEqualTo(OrderStatus.ERROR);
		assertThat(status.grantable()).isFalse();
	}

	/** 플랫폼이 상태를 추가해도 조용히 지급되지 않는다 — 비정상과 같은 경로로 접는다. */
	@Test
	@DisplayName("미문서화 상태 문자열은 ERROR 로 접는다")
	void 미문서화_상태() {
		assertThat(TossOrderStatus.of("BRAND_NEW_STATUS", SKU).status()).isEqualTo(OrderStatus.ERROR);
	}

	/** 확인하지 못한 주문은 상태도 상품 코드도 없다 — 지급 판정의 근거가 아예 없는 상태다. */
	@Test
	@DisplayName("확인 실패는 상태도 상품 코드도 없고 지급 대상이 아니다")
	void 확인_실패() {
		TossOrderStatus status = TossOrderStatus.unavailable();

		assertThat(status.available()).isFalse();
		assertThat(status.status()).isNull();
		assertThat(status.sku()).isNull();
	}

	/**
	 * 지급 대상은 {@code PURCHASED} 와 {@code PAYMENT_COMPLETED} 둘뿐이다.
	 * ⚠️ 후자는 <b>우리 판단</b>이라 뒤집힐 수 있다 — 뒤집히면 이 표가 먼저 빨개진다.
	 */
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
