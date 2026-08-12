package kang20.ytcreator.payment.internal.handler.inbound;

import jakarta.validation.Valid;
import kang20.ytcreator.auth.CurrentUser;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.PaymentPurchasePort;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.payment.dto.GrantRequest;
import kang20.ytcreator.payment.dto.GrantResult;
import kang20.ytcreator.payment.dto.ProductCatalog;
import kang20.ytcreator.payment.dto.SubscriptionSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 HTTP 진입점. <b>익명키는 더 이상 이 모듈에 들어오지 않는다</b>(auth v4 — JWT 전환) —
 * 요청 주체는 게이트({@code JwtAuthenticationFilter})가 서명 검증으로 확정한 {@link UserId} 이고
 * {@code @CurrentUser} 리졸버가 주입한다(auth-design.md §14-2). {@code AuthPort} 의존이 소멸했다.
 *
 * <p>v3 까지의 "첫 결제 사용자 자동 등록"은 v4 에서 성립하지 않는다 — 토큰을 가졌다는 것 자체가
 * 부트스트랩(=로그인·등록)을 거쳤다는 뜻이다.
 *
 * <p>다른 모듈이 참조할 일이 없는 HTTP 어댑터라 {@code internal/handler/inbound} 에 있다.
 * <b>구현체({@code PaymentService})를 직접 참조하지 않는다</b> — 필요한 포트만 주입받는다:
 * 조회는 {@link PaymentReaderPort}, 구매/재확인은 {@link PaymentPurchasePort}
 * (architecture.md "Port·Service·Support 규약").
 *
 * <p>{@code /products} 는 공개 경로(SecurityConfig PUBLIC_PATHS)라 토큰 없이 응답한다.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

	private final PaymentReaderPort readerPort;
	private final PaymentPurchasePort purchasePort;

	public PaymentController(PaymentReaderPort readerPort, PaymentPurchasePort purchasePort) {
		this.readerPort = readerPort;
		this.purchasePort = purchasePort;
	}

	/** U1 — 공개. 요청 주체를 받지 않는다. */
	@GetMapping("/products")
	public ProductCatalog products() {
		return readerPort.products();
	}

	/** U2·U3·U4·U12 — 지급(멱등). */
	@PostMapping("/grant")
	public GrantResult grant(@CurrentUser UserId userId,
			@Valid @RequestBody GrantRequest request) {
		return purchasePort.grant(userId, request.orderId());
	}

	/** U5 — 이용권 상태. */
	@GetMapping("/entitlement")
	public EntitlementView entitlement(@CurrentUser UserId userId) {
		return readerPort.entitlementOf(userId);
	}

	/** §4-7-1⑥ — STALE 해소. */
	@PostMapping("/subscription/recheck")
	public EntitlementView recheck(@CurrentUser UserId userId,
			@Valid @RequestBody SubscriptionSnapshot request) {
		return purchasePort.recheck(userId, request);
	}
}
