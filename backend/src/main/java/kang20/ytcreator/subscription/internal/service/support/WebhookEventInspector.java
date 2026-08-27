package kang20.ytcreator.subscription.internal.service.support;

import static kang20.ytcreator.shared.exception.ErrorCode.AUTH_002;
import static kang20.ytcreator.shared.exception.ErrorCode.COMMON_001;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import kang20.ytcreator.shared.exception.BusinessException;
import kang20.ytcreator.shared.support.Support;
import kang20.ytcreator.subscription.dto.WebhookEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/**
 * 웹훅을 <b>반영해도 되는가</b>를 한 번에 판정한다 — 진위·종류·형식 셋을 한 자리에서 본다.
 * 반영 자체는 하지 않는다.
 *
 * <p>🔴 <b>셋을 쪼개지 않는 이유는 순서가 곧 계약이라서다.</b>
 * <ol>
 *   <li><b>진위</b>가 먼저다 — 형식 판정이 앞서면 위조 요청이 401 대신 400 을 받고, 본문 모양에 따라
 *       응답이 갈려 페이로드 구조가 새어 나간다({@code SubscriptionWebhookControllerTest} 가 고정한 계약).</li>
 *   <li><b>종류</b>가 그다음이다 — 한 URL 로 스키마가 다른 이벤트 2종이 온다({@link WebhookEvent}).
 *       등록 검증 이벤트에는 {@code orderId}·스냅샷이 아예 없어서, 종류를 가르기 전에 형식을 물으면
 *       <b>콜백 URL 이 활성화되지 않는다</b>.</li>
 *   <li><b>형식</b>이 마지막이다.</li>
 * </ol>
 * 호출자가 이 순서를 지켜야 하는 구조였다면 한 줄을 빠뜨리는 것이 조용한 보안 구멍이 된다.
 * 순서를 클래스 안으로 넣어 <b>틀릴 여지 자체를 없앤다</b>.
 *
 * <p>⚠️ 컨트롤러의 {@code @Valid} 로는 이 판정을 대신할 수 없다 — 빈 검증은 스키마가 하나일 때만
 * 성립하고, 진위 검증보다 <b>먼저</b> 돌아 1번 순서를 뒤집는다.
 *
 * <p>진위 검증 수단은 콘솔에 등록한 <b>Basic Auth 헤더 값 대조</b>가 전부다
 * (payment.md 참고자료 ④-1) — 서명·HMAC 은 없다. ⚠️ <b>헤더 이름을 우리가 고를 수 없다</b>:
 * 콘솔에는 값 입력란만 있어서 임의 헤더로 막으면 모든 웹훅이 거부된다(2026-08-17 원문 재확인).
 *
 * <p>⚠️ <b>이 판정이 방어의 전부가 아니다.</b> 위조 웹훅의 최대 피해를 "없는 구독 생성"에서
 * "이미 결제한 사람의 상태 흔들기"로 줄이는 것은 <b>"웹훅으로 구독을 만들지 않는다"</b> 규칙 쪽이다.
 */
@Support
public class WebhookEventInspector {

	/** 플랫폼이 고정으로 붙이는 인증 스킴. 값과 달리 비밀이 아니다. */
	private static final String BASIC_PREFIX = "Basic ";

	/** 기대하는 Basic Auth 헤더 값(스킴 제외). 설정이 비면 {@code null}(항상 거부). */
	private final byte[] expectedSecret;

	/**
	 * 🔴 <b>시크릿을 코드에 박지 않는다.</b> 설정이 비어 있으면 <b>항상 거부</b>한다 —
	 * 열려 있는 채로 뜨는 것보다 낫다. 기본값을 박으면 그 기본값이 곧 공개된 시크릿이 된다.
	 */
	public WebhookEventInspector(@Value("${ytcreator.subscription.webhook.secret:}") String secret) {
		this.expectedSecret = StringUtils.hasText(secret) ? secret.getBytes(StandardCharsets.UTF_8) : null;
	}

	/**
	 * 반영해야 할 상태 변경 웹훅인가.
	 *
	 * @param authorization {@code Authorization} 헤더 값 전체({@code "Basic ..."}).
	 *                      헤더가 없으면 {@code null} 이고, 그것도 <b>인증 실패</b>다(형식 오류가 아니다)
	 * @return 콜백 URL 등록 검증 이벤트면 {@code false} — 본문 처리 없이 수신 성공만 답해야 한다
	 *         (이걸 정상 수신해야 URL 이 활성화된다)
	 * @throws BusinessException {@code AUTH_002} 진위 검증 실패 · {@code COMMON_001} 상태 변경 이벤트인데
	 *                           주문 식별자(구독을 찾는 유일한 키)나 변경 후 스냅샷(반영의 유일한 근거)이 없을 때
	 */
	public boolean shouldApply(String authorization, WebhookEvent event) {
		if (!isAuthentic(authorization)) {
			throw new BusinessException(AUTH_002);
		}
		if (WebhookEvent.TYPE_REGISTRATION_VERIFICATION.equals(event.eventType())) {
			return false;
		}
		if (event.orderId() == null || event.subscription() == null
			|| event.subscription().current() == null) {
			throw new BusinessException(COMMON_001);
		}
		return true;
	}

	/**
	 * 스킴 대조는 평범한 {@code startsWith} 지만 <b>값 대조는 상수 시간</b>이다 — 스킴은 공개된 고정
	 * 문자열이라 타이밍으로 새어도 잃을 것이 없고, 값은 그렇지 않다.
	 */
	private boolean isAuthentic(String authorization) {
		if (expectedSecret == null || authorization == null || !authorization.startsWith(BASIC_PREFIX)) {
			return false;
		}
		byte[] presented = authorization.substring(BASIC_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expectedSecret, presented);
	}
}
