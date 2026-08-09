/**
 * 인증 — 익명키로 사용자를 등록·식별한다(멱등).
 *
 * <p>이 모듈은 <b>"이 익명키의 사용자가 존재한다"는 사실</b>에만 권위를 갖는다 — 구독·결제·작업은 모른다.
 * 공개 타입은 {@link kang20.ytcreator.auth.AuthService} 와
 * {@link kang20.ytcreator.auth.dto.Registration} 뿐이다.
 *
 * <p>⚠️ <b>subscription 을 영원히 참조하지 않는다</b> — 진입 응답 조립은 집계 모듈이 한다.
 * 게이트 부품도 이 모듈이 아니라 {@code shared/security} 에 있다(auth-design.md §2-1 쟁점 3·§2-2).
 */
@ApplicationModule(displayName = "인증", allowedDependencies = {"shared"})
package kang20.ytcreator.auth;

import org.springframework.modulith.ApplicationModule;
