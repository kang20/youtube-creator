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

/**
 * {@code @CurrentUser} 리졸버 — auth-design.md §14-2 ("주입 전용 — 인가는 하지 않는다").
 *
 * <p>통합 경로(필터 → principal → 리졸버 → 컨트롤러 파라미터)는 {@code PaymentControllerTest} 가
 * 실제 요청으로 검증한다. 여기는 그 경로에서 재현하기 어려운 <b>지원 판정과 오용 방어</b>만 본다 —
 * 특히 "공개 경로에 {@code @CurrentUser} 를 잘못 붙인 개발 오류"는 실제 컨트롤러를 만들지 않고는
 * 통합으로 재현할 수 없다.
 */
class CurrentUserArgumentResolverTest {

	private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/** 리졸버 판정 대상 시그니처 견본 — 각 파라미터가 지원 매트릭스의 한 칸이다. */
	@SuppressWarnings("unused")
	private static void probe(@CurrentUser UserId annotated, UserId unannotated, @CurrentUser Long wrongType) {
	}

	private static MethodParameter parameter(int index) throws NoSuchMethodException {
		Method method = CurrentUserArgumentResolverTest.class
			.getDeclaredMethod("probe", UserId.class, UserId.class, Long.class);
		return new MethodParameter(method, index);
	}

	/**
	 * §14-2 — 지원 조건은 <b>어노테이션 + {@code UserId} 타입</b> 둘 다다. 타입 축을 빼면
	 * {@code @CurrentUser Long} 같은 오용이 조용히 다른 리졸버로 흘러 원시 Long 혼용
	 * (payment-design §2-1 쟁점 1 이 막은 것)이 되살아난다.
	 */
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

	/** U8 — 게이트가 확정한 {@code UserAuthentication} 의 {@code UserId} 를 그대로 주입한다. */
	@Test
	@DisplayName("인증돼 있으면 게이트가 확정한 UserId 를 주입한다")
	void 인증된_요청은_UserId_를_받는다() throws Exception {
		UserId userId = new UserId(7L);
		SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(userId));

		assertThat(resolver.resolveArgument(parameter(0), null, null, null)).isEqualTo(userId);
	}

	/**
	 * §14-2 — 인증이 없는데 {@code @CurrentUser} 를 만나는 것은 <b>공개 경로에 어노테이션을 잘못
	 * 붙인 개발 오류</b>다. 조용히 null 을 주입하는 대신 {@code AUTH_001} 로 드러낸다.
	 */
	@Test
	@DisplayName("인증이 없으면 null 주입 대신 AUTH_001 로 드러낸다 — 공개 경로 오용 방어")
	void 인증이_없으면_AUTH_001() throws Exception {
		MethodParameter parameter = parameter(0);
		assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_001);
	}

	/** {@code UserAuthentication} 이 아닌 인증(다른 필터가 꽂은 것)도 주입 대상이 아니다 — 같은 방어선. */
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
