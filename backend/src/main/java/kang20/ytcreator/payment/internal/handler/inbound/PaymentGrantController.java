package kang20.ytcreator.payment.internal.handler.inbound;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import kang20.ytcreator.auth.CurrentUser;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.payment.internal.port.PaymentPurchasePort;
import kang20.ytcreator.payment.dto.GrantResult;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentGrantController {
	private final PaymentPurchasePort paymentPurchasePort;

	@PostMapping("/api/v1/payments/grant")
	public GrantResult grant(@CurrentUser UserId userId, @Valid @RequestBody GrantOrderRequest request) {
		return paymentPurchasePort.grant(userId, new OrderId(request.orderId()));
	}

	record GrantOrderRequest(@NotBlank String orderId) {
	}
}
