package kang20.ytcreator.subscription.internal.port;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import kang20.ytcreator.subscription.dto.WebhookEvent;

/**
 * 구독 상태 반영 포트 — 이미 존재하는 구독의 상태를 갱신하는 <b>두 경로</b>가 여기로 들어온다
 * (new-domain/payment.md 구독 애그리거트).
 *
 * <p>소비자 축이 하나라 포트도 하나다 — 둘 다 <b>이 모듈의 HTTP 컨트롤러</b>가 부르는
 * inbound driving port 다. 둘을 갈라 포트를 둘로 만들면 "누가 무엇을 쓰는가"가 더 드러나지 않고
 * 표면만 넓어진다(architecture.md 포트 선례 — payment 가 4개를 세웠다 1개로 되돌렸다).
 * 밖에서 부르는 소비자가 없어 <b>모듈 루트가 아니라 여기 있다</b>(architecture.md "공개 표면", R1).
 *
 * <p><b>둘의 지위가 다르다</b>: 웹훅은 <b>정본</b>이고, 재확인은 사용자가 클라이언트 값으로 하는
 * <b>임시 보정</b>이다. 그래서 재확인은 웹훅 순서 판단의 기준값을 건드리지 않는다.
 *
 * <p>어느 쪽도 <b>구독을 새로 만들지 않는다.</b> 구독은 검증된 주문을 거쳐서만 생긴다
 * ({@link SubscriptionGrantPort}).
 */
public interface SubscriptionStatusPort {

	/**
	 * 웹훅을 수신해 반영한다.
	 *
	 * <p>🔴 <b>진위 검증에 실패하면 거부한다.</b> 웹훅은 위조될 수 있고, 검증은 그 1차 방어다
	 * (최대 피해를 줄이는 것은 "웹훅으로 구독을 만들지 않는다" 규칙 쪽이다).
	 *
	 * <p><b>반영에 실패하면 실패로 답한다</b>(2026-08-18 결정 — 전에는 삼켜서 204 로 답했다).
	 * 플랫폼에 재전송 정책이 없어 어느 쪽이든 그 사건은 다시 오지 않으므로 <b>유실량은 같고</b>,
	 * 삼키면 실패가 응답에서 사라져 관측만 잃는다. 유실 구간은 재확인 경로가 보정한다.
	 *
	 * <p>과거·중복 웹훅은 <b>오류가 아니라 무시</b>다. 순서 판정은 잠금을 쥔 갱신이 하며, 이미 더
	 * 나중 것이 반영돼 있으면 아무것도 바꾸지 않는다. <b>모르는 주문</b>도 무시다 — 웹훅으로
	 * 구독을 만들지 않는다.
	 *
	 * @param secret {@code Authorization} 헤더 값({@code "Basic ..."}). 비어 있으면 거부된다
	 * @param event  웹훅 페이로드
	 * @throws kang20.ytcreator.shared.exception.BusinessException {@code AUTH_002} — 진위 검증 실패,
	 *         {@code COMMON_001} — 주문 식별자·스냅샷이 없거나 모르는 상태 어휘
	 */
	void handleWebhook(String secret, WebhookEvent event);

	/**
	 * 재확인 — 상태 미확인 구간을 클라이언트가 읽어 온 값으로 <b>임시 보정</b>한다.
	 *
	 * <p>🔴 <b>상태 미확인일 때만 반영한다.</b> 아니면 아무것도 하지 않는다 — 미확인이 아닌 구독을
	 * 클라이언트 값으로 덮으면, 정본(웹훅)이 멀쩡히 살아 있는 구간을 검증되지 않은 값이 이긴다.
	 * 구독 이력이 없을 때도 마찬가지로 보정할 것이 없다.
	 *
	 * <p>🔴 <b>대상은 스냅샷의 {@code orderId} 가 지목하고 소유자는 {@code userId} 가 확정한다.</b>
	 * 지목만으로 반영하면 {@code orderId} 가 곧 권한이 된다. 모르는 주문과 <b>남의 주문</b>은 같은
	 * 무동작이다 — 갈라 답하면 주문 존재 여부가 새어 나간다.
	 *
	 * <p>⚠️ 반영하더라도 웹훅 순서 판단의 기준값은 건드리지 않는다 — 건드리면 뒤늦게 도착한 웹훅이
	 * 과거 이벤트로 버려져 클라이언트 값이 영구히 정본이 된다.
	 *
	 * <p>⚠️ <b>응답 본문이 없다.</b> 보정 후의 이용권 상태를 내려주려면 횟수권과 합성하는 읽기
	 * 모델이 필요한데 아직 없다 — 소비자 없는 DTO 를 미리 만들지 않는다. 읽기 모델이 생기면 그때
	 * 반환형을 얹는다.
	 *
	 * @throws kang20.ytcreator.shared.exception.BusinessException {@code COMMON_001} — 알 수 없는 상태 값
	 */
	void recheck(UserId userId, SubscriptionSnapshot fromClient);
}
