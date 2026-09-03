package kang20.ytcreator.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentUsageServiceTest {

	private final PaymentUsageService paymentUsageService = new PaymentUsageService();

	@Test
	@DisplayName("소모는 전부 거부된다 — 게이트 없는 개방보다 안전한 쪽")
	void 소모는_전부_거부된다() {
		assertThatThrownBy(() -> paymentUsageService.consume(new UserId(1L), "77"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_001);
	}

	@Test
	@DisplayName("확정과 해제는 도달 불능 경로다")
	void 확정과_해제는_도달_불능_경로다() {
		assertThatThrownBy(() -> paymentUsageService.commit("77"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> paymentUsageService.release("77"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
