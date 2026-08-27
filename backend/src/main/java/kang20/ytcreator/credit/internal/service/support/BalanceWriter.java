package kang20.ytcreator.credit.internal.service.support;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.credit.internal.entity.CreditBalance;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.shared.support.Support;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Support
@RequiredArgsConstructor
public class BalanceWriter {
	private final CreditBalanceRepository creditBalanceRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CreditBalance open(UserId userId) {
		return creditBalanceRepository.saveAndFlush(CreditBalance.create(userId));
	}
}
