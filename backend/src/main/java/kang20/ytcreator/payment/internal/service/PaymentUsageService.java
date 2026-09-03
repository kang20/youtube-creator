package kang20.ytcreator.payment.internal.service;

import org.springframework.stereotype.Service;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.PaymentUsagePort;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;

@Service
public class PaymentUsageService implements PaymentUsagePort {

	@Override
	public void consume(UserId userId, String jobRef) {
		throw new BusinessException(ErrorCode.PAY_001);
	}

	@Override
	public void commit(String jobRef) {
		throw new UnsupportedOperationException("PaymentUsagePort 구현 전 — 확정할 소모가 존재할 수 없다");
	}

	@Override
	public void release(String jobRef) {
		throw new UnsupportedOperationException("PaymentUsagePort 구현 전 — 되돌릴 소모가 존재할 수 없다");
	}
}
