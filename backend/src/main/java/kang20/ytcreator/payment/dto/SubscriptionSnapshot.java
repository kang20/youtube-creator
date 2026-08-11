package kang20.ytcreator.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * recheck 요청 본문(payment-design.md §5-5) — 클라가 SDK {@code getSubscriptionInfo} 로
 * 얻어 보낸 구독 스냅샷이다.
 *
 * <p>🔴 <b>검증 없이 신뢰한다</b> — payment.md §4-7-1⑧ⓐ가 "알고 여는 구멍"으로 확정했고,
 * §12-3 사용자 결정(2026-08-11)이 "일단 그대로 신뢰·출시 전 재결정"이다.
 *
 * @param status    구독 상태 6종 문자열 — 알 수 없는 값은 COMMON_001 로 거부한다
 * @param expiresAt 만료 예정 — <b>오프셋 포함 형식</b>으로 받아 KST 로 환산해 저장한다(✅-7·§12-4). null 허용(SDK 가 null 을 줄 수 있다)
 * @param autoRenew 자동 갱신 여부
 */
public record SubscriptionSnapshot(@NotBlank String status, OffsetDateTime expiresAt,
		@NotNull Boolean autoRenew) {
}
