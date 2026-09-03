package kang20.ytcreator.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.payment.OrderId;
import kang20.ytcreator.subscription.internal.entity.Subscription;
import kang20.ytcreator.subscription.internal.handler.outbound.repository.SubscriptionRepository;
import kang20.ytcreator.subscription.internal.service.SubscriptionService;
import kang20.ytcreator.subscription.internal.service.support.AccessGate;
import kang20.ytcreator.subscription.internal.service.support.SubscriptionStartWriter;
import kang20.ytcreator.subscription.internal.service.support.WebhookEventInspector;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class SubscriptionStartGuardTest {

	private static final UserId USER = new UserId(6001L);
	private static final OrderId ORDER = new OrderId("guard-order");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 12, 0);

	private final SubscriptionRepository repository = mock(SubscriptionRepository.class);
	private final SubscriptionStartWriter startWriter = mock(SubscriptionStartWriter.class);

	private final SubscriptionService service = new SubscriptionService(repository, startWriter,
		new AccessGate(), mock(WebhookEventInspector.class),
		Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));

	@Test
	@DisplayName("S6 — order_id 경쟁에 지면 승자 행으로 접어 멱등 성공한다 — 예외가 나가지 않는다")
	void 주문_제약_경쟁에_지면_멱등_성공() {
		when(repository.findByOrderId(ORDER))
			.thenReturn(Optional.empty())                                        // 선판정 — 아직 없다
			.thenReturn(Optional.of(Subscription.start(ORDER, USER, NOW)));      // 승자 재조회
		when(startWriter.open(any())).thenThrow(violation());

		assertThatCode(() -> service.start(USER, ORDER)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("경쟁이 아닌 제약 위반(NOT NULL)은 멱등 성공으로 둔갑하지 않고 그대로 터진다")
	void 경쟁이_아닌_위반은_그대로_터진다() {
		DataIntegrityViolationException notNull = new DataIntegrityViolationException("not null",
			new ConstraintViolationException("not null", new SQLException("sql"),
				ConstraintKind.NOT_NULL, "subscriptions.expires_at"));
		when(repository.findByOrderId(ORDER)).thenReturn(Optional.empty());
		when(startWriter.open(any())).thenThrow(notNull);

		assertThatThrownBy(() -> service.start(USER, ORDER)).isSameAs(notNull);
	}

	@Test
	@DisplayName("S6 — 이미 그 주문의 구독이 있으면 삽입을 시도하지 않는다")
	void 선판정에서_걸리면_삽입하지_않는다() {
		when(repository.findByOrderId(ORDER)).thenReturn(Optional.of(Subscription.start(ORDER, USER, NOW)));

		service.start(USER, ORDER);

		verify(startWriter, never()).open(any());
	}

	@Test
	@DisplayName("개시 시 추정 만료는 주입된 Clock 기준 +31일이다")
	void 추정_만료는_주입된_시계에서_나온다() {
		when(repository.findByOrderId(ORDER)).thenReturn(Optional.empty());

		service.start(USER, ORDER);

		ArgumentCaptor<Subscription> opened = ArgumentCaptor.forClass(Subscription.class);
		verify(startWriter).open(opened.capture());
		assertThat(opened.getValue().getUserId()).isEqualTo(USER);
		assertThat(opened.getValue().getOrderId()).isEqualTo(ORDER);
		assertThat(opened.getValue().getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 12, 0));
	}

	private static DataIntegrityViolationException violation() {
		return new DataIntegrityViolationException("unique",
			new ConstraintViolationException("unique", new SQLException("sql"),
				ConstraintKind.UNIQUE, Subscription.UK_ORDER_ID));
	}
}
