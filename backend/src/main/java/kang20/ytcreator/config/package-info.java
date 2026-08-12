/**
 * 전역 스프링 설정 — 보안·웹·시간 빈 정의만 둔다. 비즈니스 로직 금지.
 *
 * <p>{@code auth} 의존은 <b>게이트 부품 조립</b> 한정이다(auth-design.md §14-1) —
 * {@code SecurityConfig}(필터·진입점)·{@code WebConfig}(리졸버)가 auth 모듈 루트의 공개 계약을 꽂는다.
 * 도메인 로직 호출이 아니다.
 */
@ApplicationModule(displayName = "전역 설정", type = ApplicationModule.Type.OPEN,
		allowedDependencies = {"shared", "auth"})
package kang20.ytcreator.config;

import org.springframework.modulith.ApplicationModule;
