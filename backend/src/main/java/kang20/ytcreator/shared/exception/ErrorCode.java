package kang20.ytcreator.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 — {DOMAIN}_{NNN} 규약. 도메인별 섹션으로 묶고 새 도메인은 섹션을 추가한다.
 * 상세: docs/rule/error-handling.md
 */
public enum ErrorCode {

	// ── 공통 ──────────────────────────────────────────────
	COMMON_001(HttpStatus.BAD_REQUEST, "유효하지 않은 입력값입니다."),
	COMMON_002(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// ── 인증/인가 ─────────────────────────────────────────
	AUTH_001(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	AUTH_002(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 정보입니다."),
	AUTH_003(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getMessage() {
		return message;
	}

	/** 응답 본문의 code 필드 — enum 이름을 그대로 쓴다(COMMON_001 등). */
	public String getCode() {
		return name();
	}
}
