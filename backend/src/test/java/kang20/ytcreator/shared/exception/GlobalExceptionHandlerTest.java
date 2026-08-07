package kang20.ytcreator.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import kang20.ytcreator.shared.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	@DisplayName("BusinessException 은 ErrorCode 의 상태·코드·메시지로 변환된다")
	void 비즈니스_예외() {
		ResponseEntity<ErrorResponse> response = handler.handleBusiness(new BusinessException(ErrorCode.AUTH_001));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().code()).isEqualTo("AUTH_001");
		assertThat(response.getBody().message()).isEqualTo(ErrorCode.AUTH_001.getMessage());
	}

	@Test
	@DisplayName("로그용 상세 사유를 넣어도 사용자 응답 메시지는 ErrorCode 의 것을 쓴다")
	void 비즈니스_예외_상세사유() {
		BusinessException exception = new BusinessException(ErrorCode.AUTH_002, "키 형식 불일치: abc");

		ResponseEntity<ErrorResponse> response = handler.handleBusiness(exception);

		assertThat(exception.getMessage()).isEqualTo("키 형식 불일치: abc");
		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_002);
		assertThat(response.getBody().message()).isEqualTo(ErrorCode.AUTH_002.getMessage());
	}

	@Test
	@DisplayName("검증 실패는 COMMON_001 + 첫 필드 오류 메시지를 내려준다")
	void 검증_실패() {
		MethodArgumentNotValidException exception = Mockito.mock(MethodArgumentNotValidException.class);
		BindingResult bindingResult = Mockito.mock(BindingResult.class);
		Mockito.when(exception.getBindingResult()).thenReturn(bindingResult);
		Mockito.when(bindingResult.getFieldErrors())
			.thenReturn(List.of(new FieldError("form", "title", "제목은 필수입니다.")));

		ResponseEntity<ErrorResponse> response = handler.handleValidation(exception);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().code()).isEqualTo("COMMON_001");
		assertThat(response.getBody().message()).isEqualTo("제목은 필수입니다.");
	}

	@Test
	@DisplayName("필드 오류가 없으면 COMMON_001 의 기본 메시지를 쓴다")
	void 검증_실패_필드오류_없음() {
		MethodArgumentNotValidException exception = Mockito.mock(MethodArgumentNotValidException.class);
		BindingResult bindingResult = Mockito.mock(BindingResult.class);
		Mockito.when(exception.getBindingResult()).thenReturn(bindingResult);
		Mockito.when(bindingResult.getFieldErrors()).thenReturn(List.of());

		ResponseEntity<ErrorResponse> response = handler.handleValidation(exception);

		assertThat(response.getBody().message()).isEqualTo(ErrorCode.COMMON_001.getMessage());
	}

	@Test
	@DisplayName("본문 파싱 실패는 COMMON_001")
	void 본문_파싱_실패() {
		HttpInputMessage inputMessage = new MockHttpInputMessage("{ 깨진".getBytes(StandardCharsets.UTF_8));

		ResponseEntity<ErrorResponse> response =
			handler.handleNotReadable(new HttpMessageNotReadableException("깨진 JSON", inputMessage));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().code()).isEqualTo("COMMON_001");
	}

	@Test
	@DisplayName("접근 거부는 AUTH_003")
	void 접근_거부() {
		ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().code()).isEqualTo("AUTH_003");
	}

	@Test
	@DisplayName("처리되지 않은 예외는 COMMON_002 로 감싼다 — 내부 메시지를 노출하지 않는다")
	void 예상하지_못한_예외() {
		ResponseEntity<ErrorResponse> response =
			handler.handleUnexpected(new IllegalStateException("DB 커넥션 풀 고갈"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().code()).isEqualTo("COMMON_002");
		assertThat(response.getBody().message()).doesNotContain("DB 커넥션 풀");
	}
}
