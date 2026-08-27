package kang20.ytcreator.payment;

import kang20.ytcreator.auth.UserId;

/**
 * 이용 포트 — 작업 도메인이 소모·확정·해제를 부르는 유일한 표면이다.
 * 이름의 정본은 payment-v2 용어 사전, 시그니처의 정본은 subtitle-v1 '이용권 연동'이다.
 *
 * <p>⚠️ 구현체가 오기 전까지는 전부 거부하는 임시 어댑터로 조립된다 —
 * 게이트 없는 개방보다 안전한 쪽으로 눕는다(subtitle-v1 이용권 연동).
 */
public interface PaymentUsagePort {

	/**
	 * 쓸 수 있는지 묻고 쓰겠다고 알리는 <b>한 호출</b>이다 — 묻기와 쓰기를 갈라 부르면 그 사이에 잔량이 사라진다.
	 * 통과하지 못하면 결제 계열의 오류({@code BusinessException})로 거부된다.
	 *
	 * 예약인지 즉시 차감인지는 구현체가 정한다 — 즉시 차감이면 {@link #commit} 은 무동작이어도 된다.
	 */
	void consume(UserId userId, String jobRef);

	/** 소모를 되돌릴 수 없게 확정한다 — 자막 생성 완료와 방치(ABANDONED) 실패에서 부른다. */
	void commit(String jobRef);

	/** 소모를 되돌린다 — 서버 귀책 실패 전용이다. 환불이 아니다. */
	void release(String jobRef);
}
