/**
 * 전역 스프링 설정 — 보안·웹·시간 빈 정의만 둔다. 비즈니스 로직 금지.
 */
@ApplicationModule(displayName = "전역 설정", type = ApplicationModule.Type.OPEN)
package kang20.ytcreator.config;

import org.springframework.modulith.ApplicationModule;
