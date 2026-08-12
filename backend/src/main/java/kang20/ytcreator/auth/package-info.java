/**
 * 인증 — 익명키로 사용자를 등록·식별한다(멱등).
 *
 * <p>이 모듈은 <b>"이 익명키의 사용자가 존재한다"는 사실</b>에만 권위를 갖는다 — 결제·이용권·작업은 모른다.
 * 공개 타입은 <b>포트</b> {@link kang20.ytcreator.auth.AuthPort} ·
 * {@link kang20.ytcreator.auth.dto.Registration} · {@link kang20.ytcreator.auth.UserId} ·
 * {@link kang20.ytcreator.auth.UserIdJavaType} 다 — 다른 모듈은 엔티티가 아니라
 * <b>타입화된 기본키</b>만 본다(architecture.md "타입화된 기본키" · auth-design v3).
 * 구현({@code internal.service.AuthService})은 {@code AuthPort} 를 구현하며 직접 참조할 수 없다.
 * {@code @Support} 부품(해시·삽입 writer)은 {@code internal.service.support} 에 있고 Service 만 참조한다
 * (architecture.md "Port·Service·Support 규약").
 *
 * <p>⚠️ <b>payment 를 영원히 참조하지 않는다</b> — {@code payment → auth} 단방향이 불변식이다
 * (payment-design.md §2-1 쟁점 4). 진입 응답 조립은 집계 모듈(bootstrap)이 한다.
 * 게이트 부품도 이 모듈이 아니라 {@code shared/security} 에 있다(auth-design.md §2-1 쟁점 3·§2-2).
 */
@ApplicationModule(displayName = "인증", allowedDependencies = {"shared"})
package kang20.ytcreator.auth;

import org.springframework.modulith.ApplicationModule;
