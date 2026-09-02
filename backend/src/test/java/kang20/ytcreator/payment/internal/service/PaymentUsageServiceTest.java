package kang20.ytcreator.payment.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-182(subtitle 요구 체크리스트) — 이용 티켓 애그리거트가 오기 전의 임시 어댑터는
 * <b>전부 거부</b>한다. 조용히 통과시키면 게이트 없는 개방이 된다 — 안전한 쪽으로 눕는다.
 */
class PaymentUsageServiceTest {

	private final PaymentUsageService paymentUsageService = new PaymentUsageService();

	/** 거부 코드는 결제 계열(PAY_001)이다 — 자막 도메인의 코드가 아니다(subtitle-v3 오류 코드 규칙) */
	@Test
	@DisplayName("소모는 전부 거부된다 — 게이트 없는 개방보다 안전한 쪽")
	void 소모는_전부_거부된다() {
		assertThatThrownBy(() -> paymentUsageService.consume(new UserId(1L), "77"))
			.isInstanceOf(BusinessException.class)
			.hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAY_001);
	}

	/** 소모가 전부 거부되므로 확정·해제까지 도달하는 경로는 없다 — 도달하면 조립이 잘못된 것이다 */
	@Test
	@DisplayName("확정과 해제는 도달 불능 경로다")
	void 확정과_해제는_도달_불능_경로다() {
		assertThatThrownBy(() -> paymentUsageService.commit("77"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> paymentUsageService.release("77"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
