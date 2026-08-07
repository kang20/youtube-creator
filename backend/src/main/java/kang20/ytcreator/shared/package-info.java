/**
 * 공유 커널 — 예외·시간·보안 등 모든 모듈이 공통으로 쓰는 것.
 *
 * <p>OPEN 모듈이므로 하위 패키지까지 다른 모듈이 참조할 수 있다. 대신 여기에는
 * <b>도메인 지식이 없는 것만</b> 둔다. 특정 도메인 냄새가 나면 그 모듈로 옮긴다.
 */
@ApplicationModule(displayName = "공유 커널", type = ApplicationModule.Type.OPEN)
package kang20.ytcreator.shared;

import org.springframework.modulith.ApplicationModule;
