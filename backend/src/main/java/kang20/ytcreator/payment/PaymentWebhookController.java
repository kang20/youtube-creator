package kang20.ytcreator.payment;

import kang20.ytcreator.payment.dto.WebhookEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토스 웹훅 수신(payment-design.md §2-1 쟁점 3) — 토스는 {@code X-Anonymous-Key} 를 보내지
 * 않으므로 익명키 게이트 밖(permitAll)이다. 대신 <b>모듈이 Basic Auth 로 다시 막는다</b>(U11).
 *
 * <p>검증 통과 후에는 <b>어떤 경우에도 204</b> 다(§5-4⑤) — 반영 실패도 수신은 성공이다.
 */
@RestController
@RequestMapping("/api/v1/webhooks/toss")
public class PaymentWebhookController {

	private final PaymentService paymentService;

	public PaymentWebhookController(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	/** U9·U10·U11·U13 — 구독 상태 변경·등록 검증 수신. */
	@PostMapping("/payment")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void receive(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestBody WebhookEvent event) {
		paymentService.handleWebhook(authorization, event);
	}
}
