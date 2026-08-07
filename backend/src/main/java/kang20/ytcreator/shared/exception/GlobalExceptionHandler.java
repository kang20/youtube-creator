package kang20.ytcreator.shared.exception;

import kang20.ytcreator.shared.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * JSON API 전역 예외 변환. 응답 본문은 항상 ErrorResponse{code, message}.
 * 상세: docs/rule/error-handling.md
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode code = e.getErrorCode();
		log.info("[business] {} - {}", code.getCode(), e.getMessage());
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
	}

	/** @Valid 검증 실패 — 첫 필드 오류 메시지를 그대로 내려 클라가 바로 보여줄 수 있게 한다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getDefaultMessage())
			.orElse(ErrorCode.COMMON_001.getMessage());
		return ResponseEntity.status(ErrorCode.COMMON_001.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_001, message));
	}

	/** 본문 파싱 실패(깨진 JSON·타입 불일치) */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		log.info("[bad-request] 본문 파싱 실패: {}", e.getMessage());
		return ResponseEntity.status(ErrorCode.COMMON_001.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_001));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
		return ResponseEntity.status(ErrorCode.AUTH_003.getStatus())
			.body(ErrorResponse.of(ErrorCode.AUTH_003));
	}

	/** 최종 안전망 — 여기 걸리면 사람이 조치해야 하므로 ERROR 레벨 (docs/ops/logging.md §3.1) */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("[unexpected] 처리되지 않은 예외", e);
		return ResponseEntity.status(ErrorCode.COMMON_002.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_002));
	}
}
