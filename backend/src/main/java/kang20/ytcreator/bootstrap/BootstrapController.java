package kang20.ytcreator.bootstrap;

import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.Registration;
import kang20.ytcreator.bootstrap.dto.BootstrapResponse;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.shared.security.AnonymousAuthentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/bootstrap} — 진입 1회 왕복(payment-design.md §2-2).
 *
 * <p><b>auth 왕복은 1회로 끝난다</b> — {@code register} 가 돌려준 {@code UserId} 를 payment 에
 * 그대로 넘기므로 payment 가 다시 해석하지 않는다.
 *
 * <p><b>부분 실패를 허용하지 않는다</b>(auth.md 확정) — 이용권 조회가 실패하면 전체 500.
 * 이 조회는 토스를 부르지 않아(§5-3) 토스 장애가 부트스트랩에 전파되지 않는다.
 *
 * <p>⚠️ {@code @Transactional} 금지(§2-2) — {@code register} 는 트랜잭션 밖 호출이 전제다.
 */
@RestController
public class BootstrapController {

	private final AuthPort authPort;
	private final PaymentReaderPort paymentReader;

	public BootstrapController(AuthPort authPort, PaymentReaderPort paymentReader) {
		this.authPort = authPort;
		this.paymentReader = paymentReader;
	}

	@PostMapping("/api/v1/bootstrap")
	public BootstrapResponse bootstrap(AnonymousAuthentication authentication) {
		Registration registration = authPort.register(authentication.getAnonymousKey());
		EntitlementView entitlement = paymentReader.entitlementOf(registration.userId());
		return new BootstrapResponse(registration.newUser(), registration.registeredAt(), entitlement);
	}
}
