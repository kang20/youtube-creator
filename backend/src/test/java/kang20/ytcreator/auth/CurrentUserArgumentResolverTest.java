package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentUserArgumentResolverTest {

	private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@SuppressWarnings("unused")
	private static void probe(@CurrentUser UserId annotated, UserId unannotated, @CurrentUser Long wrongType) {
	}

	private static MethodParameter parameter(int index) throws NoSuchMethodException {
		Method method = CurrentUserArgumentResolverTest.class
			.getDeclaredMethod("probe", UserId.class, UserId.class, Long.class);
		return new MethodParameter(method, index);
	}

	@Test
	@DisplayName("@CurrentUser 가 붙은 UserId 파라미터만 지원한다")
	void 지원_판정() throws Exception {
		assertThat(resolver.supportsParameter(parameter(0))).isTrue();
		assertThat(resolver.supportsParameter(parameter(1)))
			.as("어노테이션 없는 UserId 는 지원하지 않는다")
			.isFalse();
		assertThat(resolver.supportsParameter(parameter(2)))
			.as("@CurrentUser 라도 UserId 타입이 아니면 지원하지 않는다")
			.isFalse();
	}

	@Test
	@DisplayName("인증돼 있으면 게이트가 확정한 UserId 를 주입한다")
	void 인증된_요청은_UserId_를_받는다() throws Exception {
		UserId userId = new UserId(7L);
		SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(userId));

		assertThat(resolver.resolveArgument(parameter(0), null, null, null)).isEqualTo(userId);
	}

	@Test
	@DisplayName("인증이 없으면 null 주입 대신 AUTH_001 로 드러낸다 — 공개 경로 오용 방어")
	void 인증이_없으면_AUTH_001() throws Exception {
		MethodParameter parameter = parameter(0);
		assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_001);
	}

	@Test
	@DisplayName("UserAuthentication 이 아닌 인증 객체도 AUTH_001 이다")
	void 다른_인증_타입도_거부한다() throws Exception {
		SecurityContextHolder.getContext()
			.setAuthentication(new TestingAuthenticationToken("someone", "credentials"));

		MethodParameter parameter = parameter(0);
		assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_001);
	}
}
