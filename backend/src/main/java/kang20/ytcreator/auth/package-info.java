/**
 * 인증 — 익명키 하나로 사용자를 등록·식별한다(멱등).
 *
 * <p>이 모듈은 <b>"이 익명키에 해당하는 사용자가 존재한다"는 사실</b>에만 권위를 갖는다.
 * 구독도, 결제도, 작업도 알지 않는다.
 *
 * <p>모듈 밖에 보이는 타입은 {@link kang20.ytcreator.auth.AuthService} 와
 * {@link kang20.ytcreator.auth.dto.Registration} 뿐이다. 엔티티·리포지토리는 {@code internal} 이라
 * 다른 모듈이 참조하면 ModularityTest 가 깨진다.
 *
 * <p>⚠️ <b>auth 는 subscription 을 영원히 참조하지 않는다.</b> 진입 응답 조립은 집계 모듈
 * {@code bootstrap} 이 한다(docs/domain/auth-design.md §2-2). 그래서 허용 의존은 {@code shared} 하나뿐이고,
 * 게이트 부품(필터·진입점·마스킹)도 이 모듈이 아니라 {@code shared/security} 에 있다(§2-1 쟁점 3).
 */
@ApplicationModule(displayName = "인증", allowedDependencies = {"shared"})
package kang20.ytcreator.auth;

import org.springframework.modulith.ApplicationModule;
