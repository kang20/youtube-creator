package kang20.ytcreator.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 재확인 요청 본문 — 클라이언트가 SDK {@code getSubscriptionInfo} 로 읽어 보낸 구독 스냅샷이다
 * (new-domain/payment.md 재확인 · 참고자료 ③).
 *
 * <p>🔴 <b>정본이 아니다.</b> 웹훅이 정본이고 이 값은 상태 미확인을 푸는 <b>임시 보정</b>이다.
 * 검증 수단이 없어 그대로 신뢰하며, <b>알고 여는 구멍</b>이다. 유실 구간 자체는 로그 모니터링으로
 * 감시한다.
 *
 * <p>⚠️ SDK 응답의 {@code isAccessible} 과 {@code gracePeriodExpiresAt} 은 받지 않는다 —
 * 앞은 산식이 공개돼 있지 않은 클라이언트 값이고, 뒤는 우리가 판정에 쓰지 않는 값이다.
 *
 * @param orderId   보정 대상 주문. SDK {@code getSubscriptionInfo} 는 <b>이 값을 인자로 받으므로</b>
 *                  클라이언트가 반드시 알고 있다(응답에는 없다 — iap-spec.md §3-8). 웹훅과 같은
 *                  식별자를 쓴다.
 *                  <p>🔴 <b>이 값은 대상을 지목할 뿐 소유자를 정하지 않는다.</b> 소유자는 토큰이
 *                  확정하며, 남의 주문을 보내면 보정 없이 무동작이다 — 실패로 답하면 주문 존재
 *                  여부를 알려주는 열거 표면이 된다.
 * @param status    구독 상태 6종 문자열 — 알 수 없는 값은 입력 오류({@code COMMON_001})다
 * @param expiresAt 만료 예정. 웹훅과 <b>같은 표기</b>(timezone 없는 ISO-8601)로 받는다 — 같은 컬럼에
 *                  들어가는 값을 두 표기로 받으면 환산 규칙이 두 벌이 된다.
 *                  {@code null} 허용(SDK 가 {@code null} 을 줄 수 있다) — 비어 있으면 기존 값 유지
 * @param autoRenew 자동 갱신 예정 여부
 */
public record SubscriptionSnapshot(@NotBlank String orderId, @NotBlank String status,
		LocalDateTime expiresAt, @NotNull Boolean autoRenew) {
}
