package kang20.ytcreator.subscription.internal.entity;

import java.util.Optional;

/**
 * 구독 상태 6종 — <b>토스의 어휘를 그대로 옮긴 것이다</b>
 * (new-domain/payment.md 구독 상태 · 참고자료 ② {@code status} 6종).
 *
 * <p>⚠️ <b>우리가 만든 상태를 여기 섞지 않는다.</b> "상태 미확인(stale)"은 이 enum 의 값이 아니라
 * 시간 경과로 판정하는 별개의 축이다 — 섞으면 토스가 준 사실과 우리 추론이 한 필드에서 뭉갠다.
 *
 * <p>⚠️ <b>이 상태를 게이트에 직접 쓰지 않는다.</b> "어떤 상태인가"와 "열어줄 것인가"는 별개이고,
 * 개폐 판정은 {@code AccessGate} 가 한다. 프론트에게는 안내 문구용으로만 내려간다.
 *
 * <p>{@code internal} 에 있다 — 이번 범위에 읽기 모델이 없어 밖으로 내보낼 소비자가 없다.
 * 소비자가 생기면 모듈 루트로 올린다.
 */
public enum SubscriptionStatus {

	/** 활성. */
	ACTIVE,

	/** 결제 실패 유예 — 카드 문제로 잠깐 실패한 구간. <b>우리 정책인 만료 완충과 다른 것이다.</b> */
	IN_GRACE_PERIOD,

	/** 유예 종료 후 보류. */
	ON_HOLD,

	/** 사용자가 일시정지함. */
	PAUSED,

	/** 만료. */
	EXPIRED,

	/** 회수 또는 환불. */
	REVOKED;

	/**
	 * 문자열 → 상태. <b>모르는 값은 비어서 돌아온다</b> — 플랫폼이 어휘를 늘리면 우리가 모르는 값이
	 * 올 수 있고, 그때 예외로 터뜨릴지 무시할지는 경로마다 다르다(웹훅은 무시, 재확인은 입력 오류).
	 * 판단을 호출자에게 남긴다.
	 */
	public static Optional<SubscriptionStatus> from(String raw) {
		for (SubscriptionStatus status : values()) {
			if (status.name().equals(raw)) {
				return Optional.of(status);
			}
		}
		return Optional.empty();
	}
}
