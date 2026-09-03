package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

public interface PaymentUsagePort {

	/** 묻기와 쓰기가 한 호출이다 — 갈라 부르면 그 사이에 잔량이 사라진다. */
	void consume(UserId userId, String jobRef);

	void commit(String jobRef);

	void release(String jobRef);
}
