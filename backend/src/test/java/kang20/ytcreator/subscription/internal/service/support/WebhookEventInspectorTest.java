package kang20.ytcreator.subscription.internal.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.exception.ErrorCode;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * S12 — 웹훅 반영 가부 판정(진위·종류·형식)
 * (payment.md 참고자료 ②·④-1 · {@code WebhookEventInspector} javadoc).
 *
 * <p>설정이 비었을 때의 <b>fail-closed</b> 는 여기서만 검증할 수 있다 — 모듈 테스트는 시크릿을
 * 주입한 채로 뜨기 때문에 "설정이 없는 서버"를 재현하지 못한다.
 *
 * <p>흐름(판정→반영)은 {@code SubscriptionServiceTest} 가 보고, 여기서는 <b>판정 규칙만</b> 본다.
 */
class WebhookEventInspectorTest {

	/** 콘솔 "Basic Auth 헤더" 에 등록한 값. 스킴은 포함하지 않는다. */
	private static final String SECRET = "shared-secret-value";

	/** 토스가 실제로 보내는 헤더 모양. */
	private static final String HEADER = "Basic " + SECRET;

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 9, 1, 0, 0);

	private final WebhookEventInspector inspector = new WebhookEventInspector(SECRET);

	// ── 진위 ───────────────────────────────────────────────────────────

	@Test
	@DisplayName("등록한 값이 Basic 스킴으로 오면 통과한다")
	void 시크릿_일치() {
		assertThat(inspector.shouldApply(HEADER, statusChanged(ORDER_ID, snapshot()))).isTrue();
	}

	@Test
	@DisplayName("값이 다르면 AUTH_002 다 — 접두사만 같아도 거부다")
	void 시크릿_불일치() {
		assertUnauthorized("Basic forged");
		assertUnauthorized(HEADER + "x");   // 길이가 다른 값이 통과하면 대조가 접두사 비교로 새고 있다는 뜻이다
		assertUnauthorized("Basic " + SECRET.toUpperCase());   // 값 대조는 대소문자를 구분한다
		assertUnauthorized("Basic ");
	}

	/**
	 * 🔴 <b>스킴이 없으면 거부한다.</b> 값만 온 헤더를 통과시키면 우리가 받아들이는 모양이 둘로
	 * 늘어나고, 그중 하나는 플랫폼이 절대 보내지 않는 모양이다.
	 */
	@Test
	@DisplayName("Basic 스킴이 없으면 AUTH_002 다 — 값만 온 헤더도, 다른 스킴도")
	void 스킴_불일치() {
		assertUnauthorized(SECRET);
		// 🔴 JWT 게이트와 같은 헤더를 쓰므로 스킴 대조가 두 인증을 가르는 선이다
		assertUnauthorized("Bearer " + SECRET);
		// 스킴은 플랫폼이 고정으로 붙이는 문자열이라 그대로 대조한다
		assertUnauthorized("basic " + SECRET);
	}

	/** 헤더가 아예 없으면 {@code null} 이 들어온다 — <b>형식 오류가 아니라 인증 실패</b>다. */
	@Test
	@DisplayName("헤더가 없으면(null) 400 이 아니라 AUTH_002 다")
	void 헤더_없음() {
		assertUnauthorized(null);
	}

	/**
	 * 🔴 <b>설정이 비어 있으면 무엇이 와도 거부한다(fail-closed).</b>
	 *
	 * <p>운영 시크릿은 환경변수({@code TOSS_WEBHOOK_SECRET})로만 들어온다 — 주입에 실패한 서버가
	 * <b>열린 채로</b> 뜨는 것보다 웹훅을 전부 거부하는 편이 낫다. 코드에 기본값을 박으면 그 기본값이
	 * 곧 공개된 시크릿이 된다.
	 */
	@ParameterizedTest(name = "설정값이 [{0}] 이면 무엇이 와도 거부")
	@ValueSource(strings = {"", "   "})
	@DisplayName("시크릿 설정이 비어 있으면 항상 거부한다 — 열린 채로 뜨지 않는다")
	void 설정이_비면_항상_거부(String blank) {
		WebhookEventInspector fallenClosed = new WebhookEventInspector(blank);
		WebhookEvent event = statusChanged(ORDER_ID, snapshot());

		assertAuthFailure(() -> fallenClosed.shouldApply(HEADER, event));
		// 빈 설정에 빈 값을 맞춰 통과시키면 시크릿 없는 서버가 열린 채로 뜬다
		assertAuthFailure(() -> fallenClosed.shouldApply("Basic " + blank, event));
		assertAuthFailure(() -> fallenClosed.shouldApply(null, event));
	}

	/**
	 * 🔴 <b>진위가 형식보다 먼저다.</b> 뒤집히면 위조 요청이 본문 모양에 따라 401·400 으로 갈려
	 * 페이로드 구조가 새어 나간다.
	 */
	@Test
	@DisplayName("망가진 페이로드라도 시크릿이 틀리면 COMMON_001 이 아니라 AUTH_002 다")
	void 진위가_형식보다_먼저다() {
		WebhookEvent broken = statusChanged(null, null);

		assertThatThrownBy(() -> inspector.shouldApply("Basic forged", broken))
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.as("여기가 COMMON_001 이면 판정 순서가 뒤집혀 페이로드 구조가 응답으로 샌다")
			.isEqualTo(ErrorCode.AUTH_002);
	}

	// ── 종류 ───────────────────────────────────────────────────────────

	/**
	 * 🔴 <b>종류가 형식보다 먼저다.</b> 등록 검증 이벤트에는 {@code orderId}·스냅샷이 없어서,
	 * 뒤집히면 COMMON_001 이 나가고 <b>콜백 URL 이 활성화되지 않는다</b>.
	 */
	@Test
	@DisplayName("등록 검증 이벤트는 본문이 비어 있어도 예외 없이 false 다 — 본문 처리 없음")
	void 등록_검증_이벤트() {
		WebhookEvent registration = new WebhookEvent(WebhookEvent.TYPE_REGISTRATION_VERIFICATION,
			"1.0", null, null, null, null, null);

		assertThat(inspector.shouldApply(HEADER, registration))
			.as("여기서 터지면 웹훅 경로가 아예 안 열린다").isFalse();
	}

	// ── 형식 ───────────────────────────────────────────────────────────

	/** 🔴 셋 다 <b>NPE(500)가 아니라</b> 입력 오류여야 한다 — 방어가 한 군데만 걸리면 나머지가 500 으로 샌다. */
	@Test
	@DisplayName("orderId·subscription·current 중 하나라도 없으면 COMMON_001 이다")
	void 성립하지_않는_상태_변경() {
		assertBadRequest(statusChanged(null, snapshot()));
		assertBadRequest(new WebhookEvent("subscription.status_changed", "1.0", OCCURRED_AT,
			ORDER_ID, "test.subscription", "RENEWED", null));
		assertBadRequest(statusChanged(ORDER_ID, null));
	}

	// ── helpers ────────────────────────────────────────────────────────

	private static final String ORDER_ID = "13c9a1ff-6f0e-4a2b-9f10-5c7a1e2d3b4c";

	private void assertUnauthorized(String authorization) {
		assertAuthFailure(() -> inspector.shouldApply(authorization, statusChanged(ORDER_ID, snapshot())));
	}

	private void assertAuthFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call)
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.AUTH_002);
	}

	private void assertBadRequest(WebhookEvent event) {
		assertThatThrownBy(() -> inspector.shouldApply(HEADER, event))
			.isInstanceOf(BusinessException.class)
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.isEqualTo(ErrorCode.COMMON_001);
	}

	private static WebhookEvent.Snapshot snapshot() {
		return new WebhookEvent.Snapshot("ACTIVE", true, LocalDateTime.of(2026, 10, 1, 0, 0), true);
	}

	private static WebhookEvent statusChanged(String orderId, WebhookEvent.Snapshot current) {
		return new WebhookEvent("subscription.status_changed", "1.0", OCCURRED_AT, orderId,
			"test.subscription", "RENEWED", new WebhookEvent.SubscriptionChange(null, current));
	}
}
