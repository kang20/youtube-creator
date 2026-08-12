package kang20.ytcreator.payment.internal.handler.inbound;

import jakarta.validation.Valid;
import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.PaymentPurchasePort;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.GrantRequest;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductCatalog;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;
import kang20.ytcreator.shared.security.AnonymousAuthentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 HTTP 진입점 — <b>익명키가 payment 모듈에 들어오는 유일한 지점</b>이다(payment-design.md §2-1 쟁점 1).
 * 즉시 {@link AuthPort#register}(멱등·자동 등록)로 {@link UserId} 로 해석해 버린다 —
 * 부트스트랩을 안 거친 첫 결제 사용자도 여기서 자동 등록된다.
 *
 * <p>다른 모듈이 참조할 일이 없는 HTTP 어댑터라 {@code internal/handler/inbound} 에 있다.
 * <b>구현체({@code PaymentService})를 직접 참조하지 않는다</b> — 필요한 포트만 주입받는다:
 * 조회는 {@link PaymentReaderPort}, 구매/재확인은 {@link PaymentPurchasePort}
 * (architecture.md "Port·Service·Support 규약").
 *
 * <p>⚠️ 이 해석은 <b>트랜잭션 밖</b>이어야 한다(auth-design §6-4) — 컨트롤러에는 트랜잭션이 없어
 * 양립한다. {@code GrantWriter}(REQUIRES_NEW) 안으로 옮기면 함정 ④가 되살아난다(§6-5).
 *
 * <p>{@code /products} 는 공개 경로(SecurityConfig PUBLIC_PATHS)라 익명키 없이 응답한다.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

	private final AuthPort authPort;
	private final PaymentReaderPort readerPort;
	private final PaymentPurchasePort purchasePort;

	public PaymentController(AuthPort authPort, PaymentReaderPort readerPort,
			PaymentPurchasePort purchasePort) {
		this.authPort = authPort;
		this.readerPort = readerPort;
		this.purchasePort = purchasePort;
	}

	/** U1 — 공개. 인증 객체를 받지 않는다. */
	@GetMapping("/products")
	public ProductCatalog products() {
		return readerPort.products();
	}

	/** U2·U3·U4·U12 — 지급(멱등). */
	@PostMapping("/grant")
	public GrantResult grant(AnonymousAuthentication authentication,
			@Valid @RequestBody GrantRequest request) {
		return purchasePort.grant(resolveUserId(authentication), request.orderId());
	}

	/** U5 — 이용권 상태. */
	@GetMapping("/entitlement")
	public EntitlementView entitlement(AnonymousAuthentication authentication) {
		return readerPort.entitlementOf(resolveUserId(authentication));
	}

	/** §4-7-1⑥ — STALE 해소. */
	@PostMapping("/subscription/recheck")
	public EntitlementView recheck(AnonymousAuthentication authentication,
			@Valid @RequestBody SubscriptionSnapshot request) {
		return purchasePort.recheck(resolveUserId(authentication), request);
	}

	private UserId resolveUserId(AnonymousAuthentication authentication) {
		return authPort.register(authentication.getAnonymousKey()).userId();
	}
}
