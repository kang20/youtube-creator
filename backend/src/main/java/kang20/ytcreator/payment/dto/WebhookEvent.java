package kang20.ytcreator.payment.dto;

import java.time.LocalDateTime;

/**
 * 토스 웹훅 페이로드(payment-design.md §5-4 · iap-essentials §3-6). 이벤트는 정확히 2종이다:
 * {@code callback.registration_verification}(eventType·occurredAt 2필드뿐)과
 * {@code subscription.status_changed}.
 *
 * <p>⚠️ {@code occurredAt} 은 <b>timezone 없는 ISO-8601</b> 이다 — 수신 즉시 KST 로 해석해
 * {@code LocalDateTime} 으로 저장한다(✅-7). 멱등 이벤트 ID 가 없어 중복·역전 판별은
 * {@code occurredAt} 비교뿐이다(§6-1 C5).
 *
 * @param eventType    이벤트 종류(고정값 2종)
 * @param eventVersion 고정 {@code "1.0"}
 * @param occurredAt   발생 시각(KST 해석)
 * @param orderId      최초 구독 주문 ID — 사용자와 연결하는 유일한 고리. 검증 이벤트에는 없다
 * @param sku          상품 SKU
 * @param changeReason 변경 사유 12종 — <b>분기하지 않는다.</b> 로그에만 남긴다(§5-4)
 * @param subscription 변경 전/후 스냅샷 — 판정 기준은 {@code current} 세 값이다
 */
public record WebhookEvent(String eventType, String eventVersion, LocalDateTime occurredAt,
		String orderId, String sku, String changeReason, SubscriptionChange subscription) {

	/** U10 등록 검증 이벤트 — 본문 처리 없이 204 만 답한다. */
	public static final String TYPE_REGISTRATION_VERIFICATION = "callback.registration_verification";

	/**
	 * @param previous 변경 전 스냅샷 — {@code CREATED} 등에서 생략된다. 우리 상태와 불일치하면 유실 감지(WARN)
	 * @param current  변경 후 스냅샷 — 반영의 유일한 근거
	 */
	public record SubscriptionChange(Snapshot previous, Snapshot current) {
	}

	/**
	 * 구독 스냅샷. ⚠️ {@code accessGranted} 는 <b>쓰지 않는다</b> — 산식이 문서에 없다(payment.md §4-3).
	 * 역직렬화 필드로만 존재한다.
	 */
	public record Snapshot(String status, Boolean accessGranted, LocalDateTime expiresAt, Boolean autoRenew) {
	}
}
