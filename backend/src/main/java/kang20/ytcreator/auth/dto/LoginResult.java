package kang20.ytcreator.auth.dto;

import java.time.LocalDateTime;
import kang20.ytcreator.auth.UserId;

/**
 * 로그인 결과(auth-design.md §14-2) — v3 의 {@code Registration}(newUser·registeredAt·userId)에
 * 토큰 쌍이 붙은 형태다. bootstrap 이 HTTP 응답으로 변환한다.
 *
 * @param newUser      이번 호출로 만들어졌으면 true. 동시 등록 경쟁에서 진 경우도 false 다(§6-4)
 * @param registeredAt 등록 시각 = 행 생성 시각
 * @param userId       사용자 기본키의 타입 표현. ⚠️ <b>HTTP 응답에 싣지 않는다</b> — 서버 내부 식별자다(auth.md §5-2)
 * @param accessToken  JWT(HS256 · 30분 · sub=userId). 이후 전 API 의 {@code Authorization: Bearer}
 * @param refreshToken 갱신용 불투명 값(14일 · 회전). ⚠️ 서버는 원문을 저장하지 않는다(U10)
 */
public record LoginResult(boolean newUser, LocalDateTime registeredAt, UserId userId,
		String accessToken, String refreshToken) {
}
