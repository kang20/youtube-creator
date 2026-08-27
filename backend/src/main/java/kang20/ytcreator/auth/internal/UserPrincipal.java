package kang20.ytcreator.auth.internal;

import kang20.ytcreator.auth.Role;
import kang20.ytcreator.auth.UserId;

/**
 * access 토큰이 서명으로 확정하는 전부 — 누구인가({@link UserId})와 무엇을 할 수 있는가({@link Role}).
 * {@code JwtSupport.parse} 의 반환이자 {@code UserAuthentication} 의 재료다.
 *
 * <p><b>모듈 내부 값이다</b> — 밖으로 나가는 것은 {@code UserAuthentication} 이고, 이 record 는 검증
 * 부품과 게이트 필터 사이에서만 오간다(R1 — 밖에서 안 쓰는 타입은 모듈 루트에 두지 않는다).
 *
 * <p>DB 를 보지 않는다(U8) — 두 값 모두 토큰 클레임에서 나온다. 그 대가로 권한 변경이
 * access 수명(30분)만큼 지연되는 것은 강제 폐기와 같은 축의 수용된 트레이드오프다(§14-6).
 */
public record UserPrincipal(UserId userId, Role role) {
}
