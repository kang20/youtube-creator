package kang20.ytcreator.shared.exception;

/**
 * 비즈니스 규칙 위반. 도메인 서비스는 ErrorCode 만 알고 HTTP 상태/응답 포맷은 신경 쓰지 않는다.
 */
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	/** 로그에만 남길 상세 사유가 있을 때. 사용자 응답 메시지는 ErrorCode 의 것을 쓴다. */
	public BusinessException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
