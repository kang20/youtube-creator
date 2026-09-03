package kang20.ytcreator.shared.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	COMMON_001(HttpStatus.BAD_REQUEST, "유효하지 않은 입력값입니다."),
	COMMON_002(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

	// 401 이 네 종류인 것은 프론트 행동이 갈리기 때문이다 — AUTH_004 만 refresh 후 1회 재시도다.
	// 만료(AUTH_004)를 AUTH_002 에 섞으면 30분마다 전 사용자가 재로그인한다.
	AUTH_001(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	AUTH_002(HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 정보입니다."),
	AUTH_003(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	AUTH_004(HttpStatus.UNAUTHORIZED, "만료된 인증 정보입니다."),
	AUTH_005(HttpStatus.UNAUTHORIZED, "유효하지 않은 갱신 토큰입니다."),

	// 403 이 두 종류다 — PAY_001(결제 유도) vs PAY_007(recheck 유도). 프론트 행동이 정반대다.
	// BusinessException 메시지에 orderId 를 넣지 않는다(U14).
	PAY_001(HttpStatus.FORBIDDEN, "이용 가능한 이용권이 없습니다."),
	PAY_002(HttpStatus.CONFLICT, "결제가 아직 확정되지 않았습니다."),
	PAY_003(HttpStatus.CONFLICT, "결제가 완료되지 않은 주문입니다."),
	PAY_004(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
	PAY_005(HttpStatus.CONFLICT, "다른 사용자에게 귀속된 주문입니다."),
	// BAD_GATEWAY 도 GlobalExceptionHandler.handleBusiness 가 getStatus() 로 자동 처리한다 — 핸들러 신설 불요(§9)
	PAY_006(HttpStatus.BAD_GATEWAY, "결제 정보를 확인하지 못했습니다."),
	PAY_007(HttpStatus.FORBIDDEN, "구독 상태 확인이 필요합니다."),

	// 없는 작업과 남의 작업은 같은 코드다 — 갈라 답하면 "그 작업이 있다"는 사실이 새어 나간다.
	SUBTITLE_001(HttpStatus.NOT_FOUND, "작업을 찾을 수 없습니다."),
	SUBTITLE_002(HttpStatus.CONFLICT, "지금 상태에서는 처리할 수 없는 요청입니다."),
	SUBTITLE_003(HttpStatus.BAD_REQUEST, "원본이 받을 수 있는 한계를 넘었습니다.");

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

	public String getCode() {
		return name();
	}
}
