package kang20.ytcreator.payment;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import kang20.ytcreator.shared.domain.ValueObject;

/**
 * 주문 식별자 — 토스가 발급한 주문의 식별값(new-domain/payment.md 주문 애그리거트).
 *
 * <p><b>모듈 루트에 있다 = 공개 계약이다.</b> 근거는 포트가 아니라 <b>{@code subscription} 이 이 값을
 * 저장한다</b>는 사실이다(R1 — 밖에서 실제로 쓰는 타입만 루트에 둔다). 경계를 넘는 값이 원시 문자열이면
 * 마스킹 보장이 경계에서 끊기지만, 타입으로 넘기면 <b>보장이 값과 함께 이동한다.</b> 호출자는 원문
 * 문자열로 이 타입을 만들고, 그 뒤로는 원문을 손에 쥘 일이 없다.
 *
 * <p>⚠️ <b>영속화 어댑터({@link OrderIdConverter})도 모듈 루트에 있다</b>(2026-08-14 이동) —
 * 노출 여부의 판정 기준은 "다른 모듈이 그 값을 저장하는가"이고, {@code subscription} 이
 * <b>웹훅이 구독을 찾아오는 유일한 키</b>로 {@code order_id} 를 저장하기 시작했다. 그전까지는
 * 저장하는 모듈이 없어 {@code internal} 에 숨겨 뒀다.
 *
 * <p>🔴 <b>원문은 밖으로 나가지 않는다.</b> 응답 본문·예외 메시지·로그 어디에도 싣지 않는다 —
 * 소유자는 <b>선점</b>으로만 정해지므로, 주문 식별자를 아는 것은 <b>미지급 주문을 가로챌 수 있는 것</b>과
 * 같다. 그래서 마스킹을 호출자의 선택에 맡기지 않고 <b>{@link #toString()} 이 강제한다</b> —
 * 문자열 연결·로그 포맷·디버거·예외 메시지가 전부 마스킹된 값을 본다.
 *
 * <p>원문이 필요한 곳은 <b>토스 호출과 DB 저장 둘뿐</b>이다. 그 경로만 {@link #raw()} 를 쓴다.
 *
 * <p>마스킹 폭(앞 4자)은 <b>이 모듈의 정책</b>이다 — 익명키 마스킹({@code AnonymousKeyFormat.mask})을
 * 재사용하지 않는다. 재사용하면 auth 쪽 정책이 바뀔 때 여기 노출 폭이 조용히 함께 바뀐다.
 *
 * <p>⚠️ {@code ValueObject} 는 strict {@code getClass()} 비교라 <b>{@code final}</b> 이어야 한다.
 */
public final class OrderId extends ValueObject<OrderId> implements Serializable {

	/** 로그에서 주문을 서로 구분할 만큼만 남긴다. 더 늘리면 U14 방어가 얇아진다. */
	private static final int VISIBLE_PREFIX = 4;

	private static final String MASK = "***";

	private final String value;

	public OrderId(String value) {
		requireNonNull(value, "주문 식별자는 비어 있을 수 없다");
		if (value.isBlank()) {
			throw new IllegalArgumentException("주문 식별자는 비어 있을 수 없다");
		}
		this.value = value;
	}

	/**
	 * 원문 접근자 — <b>토스 호출과 DB 저장 전용</b>이다.
	 *
	 * <p>⚠️ 응답·로그·예외 메시지에 쓰지 마라. 그 용도에는 {@link #toString()} 이 이미 안전한 값을 준다.
	 */
	public String raw() {
		return value;
	}

	/** 앞 4자만 남긴다. 4자 미만이면 전부 가린다 — 어떤 입력에서도 원문이 새지 않는다. */
	public String masked() {
		if (value.length() < VISIBLE_PREFIX) {
			return MASK;
		}
		return value.substring(0, VISIBLE_PREFIX) + MASK;
	}

	@Override
	protected Object[] getEqualityFields() {
		return new Object[] {value};
	}

	/** 🔴 마스킹된 값이다. 원문을 반환하도록 바꾸지 마라 — 그 순간 U14 방어가 통째로 사라진다. */
	@Override
	public String toString() {
		return masked();
	}
}
