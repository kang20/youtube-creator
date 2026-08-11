package kang20.ytcreator.auth.dto;

import java.time.LocalDateTime;
import kang20.ytcreator.auth.UserId;

/**
 * 등록 결과. PK 를 <b>원시 Long 이 아니라 타입화한 {@link UserId} 로 담는다</b> —
 * 다른 모듈은 이 타입 ID 만 저장·전달한다(architecture.md "타입화된 기본키" · auth-design v3 §4).
 *
 * <p>v2 의 <i>"PK 를 담지 않는다"</i> 결정은 2026-08-11 타입화된 기본키 채택으로
 * <b>번복됐다</b>(payment-design.md §2-1 쟁점 1·§7).
 *
 * @param newUser      이번 호출로 만들어졌으면 true. 동시 등록 경쟁에서 진 경우도 false 다(§6-4)
 * @param registeredAt 등록 시각 = 행 생성 시각
 * @param userId       사용자 기본키의 타입 표현. ⚠️ <b>HTTP 응답에 싣지 않는다</b> — 서버 내부 식별자다(payment-design.md §2-2)
 */
public record Registration(boolean newUser, LocalDateTime registeredAt, UserId userId) {
}
