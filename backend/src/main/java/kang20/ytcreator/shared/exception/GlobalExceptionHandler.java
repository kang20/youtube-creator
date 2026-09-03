package kang20.ytcreator.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import kang20.ytcreator.shared.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
		ErrorCode code = e.getErrorCode();
		log.info("[business] {} - {}", code.getCode(), e.getMessage());
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getDefaultMessage())
			.orElse(ErrorCode.COMMON_001.getMessage());
		return ResponseEntity.status(ErrorCode.COMMON_001.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_001, message));
	}

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

	@ExceptionHandler(NullPointerException.class)
	public ResponseEntity<ErrorResponse> handleInvariantViolation(NullPointerException e, HttpServletRequest request) {
		log.error("[invariant] null 이 도달하면 안 되는 자리에 도달했다. {} {} — {}",
			request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(ErrorCode.COMMON_002.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_002));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		log.error("[unexpected] 처리되지 않은 예외. {} {}", request.getMethod(), request.getRequestURI(), e);
		return ResponseEntity.status(ErrorCode.COMMON_002.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_002));
	}
}
