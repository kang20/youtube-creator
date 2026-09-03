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

class WebhookEventInspectorTest {

	private static final String SECRET = "shared-secret-value";

	private static final String HEADER = "Basic " + SECRET;

	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 9, 1, 0, 0);

	private final WebhookEventInspector inspector = new WebhookEventInspector(SECRET);

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

	@Test
	@DisplayName("Basic 스킴이 없으면 AUTH_002 다 — 값만 온 헤더도, 다른 스킴도")
	void 스킴_불일치() {
		assertUnauthorized(SECRET);
		// JWT 게이트와 같은 헤더를 쓰므로 스킴 대조가 두 인증을 가르는 선이다
		assertUnauthorized("Bearer " + SECRET);
		// 스킴은 플랫폼이 고정으로 붙이는 문자열이라 그대로 대조한다
		assertUnauthorized("basic " + SECRET);
	}

	@Test
	@DisplayName("헤더가 없으면(null) 400 이 아니라 AUTH_002 다")
	void 헤더_없음() {
		assertUnauthorized(null);
	}

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

	@Test
	@DisplayName("망가진 페이로드라도 시크릿이 틀리면 COMMON_001 이 아니라 AUTH_002 다")
	void 진위가_형식보다_먼저다() {
		WebhookEvent broken = statusChanged(null, null);

		assertThatThrownBy(() -> inspector.shouldApply("Basic forged", broken))
			.extracting(thrown -> ((BusinessException) thrown).getErrorCode())
			.as("여기가 COMMON_001 이면 판정 순서가 뒤집혀 페이로드 구조가 응답으로 샌다")
			.isEqualTo(ErrorCode.AUTH_002);
	}

	@Test
	@DisplayName("등록 검증 이벤트는 본문이 비어 있어도 예외 없이 false 다 — 본문 처리 없음")
	void 등록_검증_이벤트() {
		WebhookEvent registration = new WebhookEvent(WebhookEvent.TYPE_REGISTRATION_VERIFICATION,
			"1.0", null, null, null, null, null);

		assertThat(inspector.shouldApply(HEADER, registration))
			.as("여기서 터지면 웹훅 경로가 아예 안 열린다").isFalse();
	}

	@Test
	@DisplayName("orderId·subscription·current 중 하나라도 없으면 COMMON_001 이다")
	void 성립하지_않는_상태_변경() {
		assertBadRequest(statusChanged(null, snapshot()));
		assertBadRequest(new WebhookEvent("subscription.status_changed", "1.0", OCCURRED_AT,
			ORDER_ID, "test.subscription", "RENEWED", null));
		assertBadRequest(statusChanged(ORDER_ID, null));
	}

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
