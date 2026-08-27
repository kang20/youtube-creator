/**
 * 진입 집계 — auth 등록 결과를 한 응답으로 합친다(auth-design §2-2).
 *
 * <p>⚠️ <b>이용권 합성이 빠져 있다</b> — payment 롤백(2026-08-14)으로 제거됐다. 재구현 시
 * {@code allowedDependencies} 에 {@code "payment"}·{@code "payment :: dto"} 를 되살린다.
 *
 * <p><b>자기 저장소를 갖지 않는다</b> — 엔티티가 생기면 그건 집계가 아니라 새 도메인이다(auth.md §4-7).
 *
 * <p>⚠️ <b>{@code @Transactional} 을 붙이지 마라.</b> {@code AuthService.register} 는 트랜잭션
 * 밖에서 호출돼야 한다(auth-design §6-4) — 붙이는 순간 함정 ④가 되살아난다. <b>집계는 합치기만 한다.</b>
 */
@ApplicationModule(displayName = "진입",
		allowedDependencies = {"shared", "auth", "auth :: dto"})
package kang20.ytcreator.bootstrap;

import org.springframework.modulith.ApplicationModule;
