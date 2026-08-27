/**
 * {@link kang20.ytcreator.subscription.dto.WebhookEvent} ·
 * {@link kang20.ytcreator.subscription.dto.SubscriptionSnapshot} 은 subscription 이 노출하는 dto
 * 인터페이스다 — Spring Modulith 는 모듈 루트의 public 타입만 API 로 보므로, 서브패키지 노출은 이
 * 선언이 근거다.
 *
 * <p>참조하는 모듈은 {@code allowedDependencies} 에 {@code "subscription :: dto"} 를 명시해야 한다
 * (지금은 그런 모듈이 없다 — 실질 소비자는 이 모듈의 컨트롤러다).
 *
 * <p>🔶 <b>그래서 이 노출은 아직 근거가 없다</b> — 시그니처를 담던 {@code SubscriptionStatusPort} 가
 * {@code internal/port} 로 내려갔고(R1), 밖에서 이 dto 를 쓰는 모듈도 없다. 읽기 모델(Entitlement)이
 * 생기면 소비자가 붙으므로 그때 함께 판정한다(architecture.md "공개 표면").
 */
@NamedInterface("dto")
package kang20.ytcreator.subscription.dto;

import org.springframework.modulith.NamedInterface;
