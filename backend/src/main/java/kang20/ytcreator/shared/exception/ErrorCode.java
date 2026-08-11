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
	AUTH_003(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

	// ── 결제/이용권 (payment-design.md §9) ─────────────────
	// ❗ 403 이 두 종류다 — PAY_001(결제 유도) vs PAY_007(recheck 유도). 프론트 행동이 정반대다.
	// ⚠️ BusinessException 메시지에 orderId 를 넣지 않는다(U14).
	PAY_001(HttpStatus.FORBIDDEN, "이용 가능한 이용권이 없습니다."),
	PAY_002(HttpStatus.CONFLICT, "결제가 아직 확정되지 않았습니다."),
	PAY_003(HttpStatus.CONFLICT, "결제가 완료되지 않은 주문입니다."),
	PAY_004(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
	PAY_005(HttpStatus.CONFLICT, "다른 사용자에게 귀속된 주문입니다."),
	// BAD_GATEWAY 도 GlobalExceptionHandler.handleBusiness 가 getStatus() 로 자동 처리한다 — 핸들러 신설 불요(§9)
	PAY_006(HttpStatus.BAD_GATEWAY, "결제 정보를 확인하지 못했습니다."),
	PAY_007(HttpStatus.FORBIDDEN, "구독 상태 확인이 필요합니다.");

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
