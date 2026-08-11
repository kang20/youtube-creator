package kang20.ytcreator.bootstrap.dto;

import java.time.LocalDateTime;
import kang20.ytcreator.payment.dto.EntitlementView;

/**
 * 진입 응답(auth.md §5-2 v3 확정 계약).
 *
 * <p>⚠️ <b>{@code userId} 를 싣지 않는다</b> — 확정 계약에 없는 필드이고, {@code UserId} 는
 * 서버 내부 식별자이지 프론트 계약이 아니다(payment-design.md §2-2).
 *
 * @param newUser      이번 진입으로 등록됐으면 true
 * @param registeredAt 등록 시각
 * @param entitlement  현재 이용권 상태(payment.md §5-3 정본)
 */
public record BootstrapResponse(boolean newUser, LocalDateTime registeredAt, EntitlementView entitlement) {
}
