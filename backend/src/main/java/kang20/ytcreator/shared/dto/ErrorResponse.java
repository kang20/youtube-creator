package kang20.ytcreator.shared.dto;

import kang20.ytcreator.shared.exception.ErrorCode;

/**
 * 모든 에러 응답의 공통 본문. REST Docs common.adoc 의 규격과 일치해야 한다.
 */
public record ErrorResponse(String code, String message) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.getCode(), message);
	}
}
