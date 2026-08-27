package kang20.ytcreator.subscription.internal.handler.inbound;

import jakarta.validation.Valid;
import kang20.ytcreator.auth.CurrentUser;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.subscription.internal.port.SubscriptionStatusPort;
import kang20.ytcreator.subscription.dto.SubscriptionSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재확인 — 상태 미확인 구간을 사용자가 직접 푸는 경로.
 *
 * <p>웹훅 수신과 달리 <b>인증 게이트 안</b>이다. 보정 대상이 "이 사용자의 구독"이라 소유자를 토큰이
 * 확정해야 한다 — 클라이언트가 사용자를 지목하게 두면 남의 구독을 덮을 수 있다.
 *
 * <p>⚠️ 응답 본문이 없다(204) — 보정 후 이용권 상태를 내려주려면 횟수권과 합성하는 읽기 모델이
 * 필요한데 아직 없다. 소비자 없는 DTO 를 미리 만들지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class SubscriptionRecheckController {
	private final SubscriptionStatusPort subscriptionStatusPort;

	@PostMapping("/api/v1/subscriptions/recheck")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void recheck(@CurrentUser UserId userId, @Valid @RequestBody SubscriptionSnapshot fromClient) {
		subscriptionStatusPort.recheck(userId, fromClient);
	}
}
