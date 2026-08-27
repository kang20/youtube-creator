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

	/**
	 * 불변식 위반 — {@code requireNonNull} 이 지키는 자리에 {@code null} 이 도달했다.
	 *
	 * <p>응답은 최종 안전망과 <b>똑같은 COMMON_002</b> 다. 다른 것은 로그뿐이며, 그게 이 핸들러의
	 * 존재 이유다 — 이 예외는 사용자 입력 오류가 아니라 <b>우리 코드의 버그</b>이고(클라 입력은
	 * {@code @Valid} 가 앞에서 400 으로 거른다), 버그는 <b>어느 요청이 어디서 죽었는지</b>가 남아야
	 * 고칠 수 있다. 요약 줄에 요청 경로와 사유를 실어 두면 스택을 펼치지 않고도 검색·알림이 된다.
	 */
	@ExceptionHandler(NullPointerException.class)
	public ResponseEntity<ErrorResponse> handleInvariantViolation(NullPointerException e, HttpServletRequest request) {
		log.error("[invariant] null 이 도달하면 안 되는 자리에 도달했다. {} {} — {}",
			request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(ErrorCode.COMMON_002.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_002));
	}

	/** 최종 안전망 — 여기 걸리면 사람이 조치해야 하므로 ERROR 레벨 (docs/ops/logging.md §3.1) */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
		log.error("[unexpected] 처리되지 않은 예외. {} {}", request.getMethod(), request.getRequestURI(), e);
		return ResponseEntity.status(ErrorCode.COMMON_002.getStatus())
			.body(ErrorResponse.of(ErrorCode.COMMON_002));
	}
}
