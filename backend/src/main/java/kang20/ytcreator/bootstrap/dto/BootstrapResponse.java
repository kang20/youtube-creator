package kang20.ytcreator.bootstrap.dto;

import java.time.LocalDateTime;

/**
 * 진입 응답(auth.md §5-2 v4 확정 계약 — 토큰 동봉. 부트스트랩이 곧 로그인이다).
 *
 * <p>⚠️ <b>{@code userId} 를 싣지 않는다</b> — 확정 계약에 없는 필드이고, {@code UserId} 는
 * 서버 내부 식별자이지 프론트 계약이 아니다(auth.md §5-2).
 *
 * <p>⚠️ <b>{@code entitlement} 가 빠져 있다</b> — payment 롤백(2026-08-14)으로 제거됐다.
 * 재구현 시 되살려야 프론트 계약이 복구된다.
 *
 * @param newUser      이번 진입으로 등록됐으면 true
 * @param registeredAt 등록 시각
 * @param auth         토큰 쌍 — 재호출 = 재로그인(호출마다 새로 발급, 기존 refresh 는 폐기하지 않는다)
 */
public record BootstrapResponse(boolean newUser, LocalDateTime registeredAt, AuthTokens auth) {

	/**
	 * {@code auth{}} 객체(auth.md §5-2 v4).
	 *
	 * @param accessToken  JWT(30분). 이후 전 API 의 {@code Authorization: Bearer}
	 * @param refreshToken 갱신용 불투명 값(14일·회전). 프론트는 로컬에만 보관한다
	 */
	public record AuthTokens(String accessToken, String refreshToken) {
	}
}
