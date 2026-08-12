/**
 * 인증 — 익명키로 사용자를 등록·식별하고(멱등), JWT 게이트로 요청 주체를 확정한다(auth.md v4).
 *
 * <p>이 모듈은 <b>"이 익명키의 사용자가 존재한다"는 사실과 토큰 발급·검증·회전</b>에 권위를 갖는다 —
 * 결제·이용권·작업은 모른다. 공개 타입은 포트 {@link kang20.ytcreator.auth.AuthPort} ·
 * 타입 ID {@link kang20.ytcreator.auth.UserId}/{@link kang20.ytcreator.auth.UserIdJavaType} ·
 * <b>게이트 부품</b>({@link kang20.ytcreator.auth.JwtAuthenticationFilter} ·
 * {@link kang20.ytcreator.auth.TokenAuthenticationEntryPoint} ·
 * {@link kang20.ytcreator.auth.UserAuthentication} · {@link kang20.ytcreator.auth.CurrentUser} ·
 * {@link kang20.ytcreator.auth.CurrentUserArgumentResolver}) · dto({@code auth :: dto}) 다.
 *
 * <p>게이트 부품이 {@code shared/security} 가 아니라 <b>여기</b> 있는 이유(auth-design.md §14-1 —
 * v1 쟁점 3 번복): 토큰 발급·검증·회전은 명백히 auth 도메인이고, {@code shared → auth} 는
 * 순환이라 불가능하다. 게이트 부품은 HTTP 어댑터가 아니라 <b>{@code config} 가 조립하는 공개 계약</b>이다.
 *
 * <p>구현({@code internal.service.AuthService})은 {@code AuthPort} 를 구현하며 직접 참조할 수 없다.
 * {@code @Support} 부품(해시·삽입 writer·JWT·refresh writer)은 {@code internal.service.support} 에
 * 있고 Service 만 참조한다(architecture.md "Port·Service·Support 규약").
 *
 * <p>⚠️ <b>payment 를 영원히 참조하지 않는다</b> — {@code payment → auth} 단방향이 불변식이다
 * (payment-design.md §2-1 쟁점 4). 진입 응답 조립은 집계 모듈(bootstrap)이 한다.
 */
@ApplicationModule(displayName = "인증", allowedDependencies = {"shared"})
package kang20.ytcreator.auth;

import org.springframework.modulith.ApplicationModule;
