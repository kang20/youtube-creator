package kang20.ytcreator.subscription.dto;

import java.time.LocalDateTime;

/**
 * 토스가 보내는 웹훅 페이로드 — <b>구독 상태 변화를 아는 유일한 경로</b>다
 * (new-domain/payment.md 웹훅 사건 · 참고자료 ②). 서버가 구독 상태를 물어볼 API 는 없다.
 *
 * <p>이벤트는 정확히 2종이다 — {@link #TYPE_REGISTRATION_VERIFICATION}(콜백 URL 등록·변경 시,
 * <b>이걸 정상 수신해야 URL 이 활성화된다</b>)과 {@code subscription.status_changed}.
 *
 * <p>🔴 <b>변경 사유로 분기하지 않는다.</b> {@code changeReason} 은 12종인데 {@code status} 는 6종이라
 * 사유로 분기하면 같은 결과에 열두 갈래 코드가 생긴다. 판정 근거는 변경 후 스냅샷의
 * <b>상태·만료 시각·자동 갱신 세 값</b>이고, 사유는 로그에만 남긴다.
 *
 * <p>⚠️ 시간 값은 <b>timezone 없는 ISO-8601</b> 이다(예: {@code "2026-05-06T00:00:00"}).
 * 기준 타임존이 플랫폼 원문에서 확인되지 않아, 받은 벽시계 값을 그대로 {@code LocalDateTime} 으로
 * 다룬다 — 최대 몇 시간의 오차 가능성을 <b>만료 완충</b>이 흡수한다.
 *
 * @param eventType    이벤트 종류(고정값 2종)
 * @param eventVersion 고정 {@code "1.0"}
 * @param occurredAt   발생 시각 — 이벤트 식별자가 없으므로 <b>순서 판단의 유일한 근거</b>다.
 *                     비어 올 수 있다
 * @param orderId      최초 구독 주문 식별자 — 구독과 사용자를 잇는 유일한 고리. 등록 검증 이벤트에는 없다
 * @param sku          상품 코드
 * @param changeReason 변경 사유 12종 — <b>분기하지 않는다.</b> 로그에만 남긴다
 * @param subscription 변경 전/후 스냅샷
 */
public record WebhookEvent(String eventType, String eventVersion, LocalDateTime occurredAt,
		String orderId, String sku, String changeReason, SubscriptionChange subscription) {

	/** 콜백 URL 등록 검증 이벤트 — 본문 처리 없이 수신 성공만 답한다. */
	public static final String TYPE_REGISTRATION_VERIFICATION = "callback.registration_verification";

	/**
	 * @param previous 변경 전 스냅샷 — <b>생략될 수 있다</b>({@code CREATED} 처럼 이전 상태가 없을 때).
	 *                 그래서 유실 감지는 이 값이 있을 때만 성립한다
	 * @param current  변경 후 스냅샷 — 반영의 유일한 근거
	 */
	public record SubscriptionChange(Snapshot previous, Snapshot current) {
	}

	/**
	 * 구독 스냅샷 — 필드는 넷이다.
	 *
	 * <p>⚠️ <b>{@code accessGranted} 를 쓰지 않는다.</b> 스냅샷에 들어 있지만 산식이 공개돼 있지 않다 —
	 * 역직렬화 필드로만 존재한다. "열어줄 것인가"는 우리 {@code AccessGate} 가 정한다.
	 *
	 * @param expiresAt <b>{@code null} 일 수 있다</b>({@code CREATED} 예시가 실제로 {@code null} 이다).
	 *                  비어 있으면 기존 값을 유지한다 — 정본을 무로 덮으면 만료 판정이 통째로 죽는다
	 */
	public record Snapshot(String status, Boolean accessGranted, LocalDateTime expiresAt, Boolean autoRenew) {
	}
}
