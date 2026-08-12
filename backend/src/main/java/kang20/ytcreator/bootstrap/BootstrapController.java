package kang20.ytcreator.bootstrap;

import kang20.ytcreator.auth.AuthPort;
import kang20.ytcreator.auth.dto.LoginResult;
import kang20.ytcreator.bootstrap.dto.BootstrapResponse;
import kang20.ytcreator.payment.PaymentReaderPort;
import kang20.ytcreator.payment.dto.EntitlementView;
import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.shared.security.AnonymousKeyFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/bootstrap} — 진입 1회 왕복(payment-design.md §2-2). <b>부트스트랩이 곧
 * 로그인이다</b>(auth.md U7 v4) — 응답에 access·refresh 를 동봉한다.
 *
 * <p><b>게이트 밖(공개 경로)이다</b> — 최초 사용자는 토큰이 없다. 대신 익명키 헤더가 필수이고
 * (없으면 등록할 대상이 없다 → AUTH_001), U5 형식 검증을 여기서 직접 한다(auth-design.md §14-2 —
 * v4 에서 익명키가 게이트를 떠나 이 컨트롤러의 책임이 됐다).
 *
 * <p><b>auth 왕복은 1회로 끝난다</b> — {@code login} 이 돌려준 {@code UserId} 를 payment 에
 * 그대로 넘기므로 payment 가 다시 해석하지 않는다.
 *
 * <p><b>부분 실패를 허용하지 않는다</b>(auth.md 확정) — 이용권 조회가 실패하면 전체 500.
 *
 * <p>⚠️ {@code @Transactional} 금지(§2-2) — {@code login}(등록부)은 트랜잭션 밖 호출이 전제다.
 * ⚠️ 에러 응답·로그에 익명키 원문을 넣지 마라(U6) — 필요하면 {@link AnonymousKeyFormat#mask}.
 */
@RestController
public class BootstrapController {

	/** 익명키 헤더명 — 프론트 계약이라 한 글자도 바뀌면 안 된다. v4 에서 이 엔드포인트만 받는다(auth.md §5-1). */
	public static final String ANONYMOUS_KEY_HEADER = "X-Anonymous-Key";

	private final AuthPort authPort;
	private final PaymentReaderPort paymentReader;

	public BootstrapController(AuthPort authPort, PaymentReaderPort paymentReader) {
		this.authPort = authPort;
		this.paymentReader = paymentReader;
	}

	@PostMapping("/api/v1/bootstrap")
	public BootstrapResponse bootstrap(
			@RequestHeader(name = ANONYMOUS_KEY_HEADER, required = false) String anonymousKey) {
		validate(anonymousKey);

		LoginResult login = authPort.login(anonymousKey);
		EntitlementView entitlement = paymentReader.entitlementOf(login.userId());

		return new BootstrapResponse(login.newUser(), login.registeredAt(),
			new BootstrapResponse.AuthTokens(login.accessToken(), login.refreshToken()), entitlement);
	}

	/** U5 — 헤더 없음/공백은 AUTH_001(등록 대상 없음, auth.md §4-2·§6-1), 형식 위반은 AUTH_002. */
	private void validate(String anonymousKey) {
		if (!StringUtils.hasText(anonymousKey)) {
			throw new BusinessException(ErrorCode.AUTH_001);
		}
		if (!AnonymousKeyFormat.isValid(anonymousKey)) {
			throw new BusinessException(ErrorCode.AUTH_002);
		}
	}
}
