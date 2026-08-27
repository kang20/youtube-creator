/**
 * 자막 — 돈을 낸 사용자가 어디서 이탈해도 결과물을 잃지 않게 한다(new-domain/subtitle/subtitle-v1.md).
 *
 * <p><b>애그리거트는 작업(Job) 하나다.</b> 대본·자막 파일의 실물은 객체 스토리지에 있고
 * 이 모듈이 들고 있는 것은 위치({@code StorageKey})뿐이다.
 *
 * <p><b>공개 표면은 비어 있다</b> — 포트를 부르는 것은 자기 컨트롤러·스케줄러뿐이라 전부
 * {@code internal/port} 에 있다(architecture.md R1).
 *
 * <p>이용권은 payment 의 {@code PaymentUsagePort} 하나로만 부른다 — 잔량도 구독 상태도 직접
 * 보지 않고 통과 여부만 안다. {@code auth} 에서 쓰는 것은 {@code UserId} 하나다.
 */
@ApplicationModule(displayName = "자막", allowedDependencies = {"shared", "auth", "payment"})
package kang20.ytcreator.subtitle;

import org.springframework.modulith.ApplicationModule;
