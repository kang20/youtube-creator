package kang20.ytcreator.credit.internal.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.credit.internal.port.CreditGrantPort;
import kang20.ytcreator.credit.internal.handler.outbound.repository.CreditBalanceRepository;
import kang20.ytcreator.credit.internal.service.support.BalanceWriter;
import kang20.ytcreator.shared.support.UniqueRace;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CreditService implements CreditGrantPort {
	private final CreditBalanceRepository creditBalanceRepository;
	private final BalanceWriter balanceWriter;
	private final Clock clock;

	@Override
	public void grant(UserId userId) {
		if (increment(userId)) {
			return;
		}

		UniqueRace.firstWriterWins(
			() -> {
				balanceWriter.open(userId);
				return true;
			},
			() -> increment(userId) ? Optional.of(true) : Optional.empty(),
			userId   // 판정 불가 로그용 — 대리키라 원문 노출 문제가 없다
		);
	}

	private boolean increment(UserId userId) {
		return creditBalanceRepository.increment(userId.longValue(), LocalDateTime.now(clock)) == 1;
	}
}
