package kang20.ytcreator.auth.dto;

/**
 * 회전된 새 토큰 쌍 — {@code POST /api/v1/auth/refresh} 의 응답 본문 그대로다(auth.md §5-5).
 * 요청에 쓴 refresh 는 발급 순간 폐기됐다(U9 회전).
 *
 * @param accessToken  JWT(HS256 · 30분 · sub=userId)
 * @param refreshToken 새 refresh 원문(14일). ⚠️ 서버는 원문을 저장하지 않는다(U10)
 */
public record TokenPair(String accessToken, String refreshToken) {
}
