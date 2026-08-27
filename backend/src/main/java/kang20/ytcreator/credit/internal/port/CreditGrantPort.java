package kang20.ytcreator.credit.internal.port;

import kang20.ytcreator.auth.UserId;

public interface CreditGrantPort {

	/**
	 * 잔량을 1 올린다 — 행이 있으면 원자 UPDATE, 없으면 잔량 1 짜리 첫 행을 연다.
	 *
	 * <p>동시 첫 지급 경쟁은 {@code UNIQUE(user_id)} 가 심판한다 — 진 쪽은 승자 행에
	 * 원자 UPDATE 로 합류한다(architecture.md "동시성").
	 */
	void grant(UserId userId);
}
