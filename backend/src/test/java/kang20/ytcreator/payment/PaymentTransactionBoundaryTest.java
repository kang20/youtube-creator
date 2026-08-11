package kang20.ytcreator.payment;

import kang20.ytcreator.payment.internal.entity.SubscriptionStatus;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import kang20.ytcreator.auth.AuthService;
import kang20.ytcreator.auth.UserId;
import kang20.ytcreator.bootstrap.BootstrapController;
import kang20.ytcreator.payment.dto.ProductType;
import kang20.ytcreator.payment.internal.writer.GrantWriter;
import kang20.ytcreator.payment.internal.writer.SubscriptionApplyWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment-design.md §6-2 함정 ③·④ <b>회귀 방지</b> (§10 {@code PaymentTransactionBoundaryTest}).
 *
 * <p>이 구조가 깨지는 방식은 전부 <b>H2 에서는 통과하고 운영 MySQL 에서만 터진다</b>(함정 ③ —
 * H2 는 READ COMMITTED) 또는 <b>부하가 걸려야 터진다</b>(함정 ④ — 커넥션 풀 고갈).
 * 기능 테스트로는 잡히지 않으므로 어노테이션·의존 자체를 단언한다.
 */
class PaymentTransactionBoundaryTest {

	/**
	 * §6-2 — {@code grant} 에 트랜잭션이 <b>없어야</b> 한다. 붙는 순간
	 * ⓐ 토스 왕복이 커넥션을 물고(함정 ④ — 30초 예산과 풀이 동시에 무너진다)
	 * ⓑ 경쟁 패자의 재조회(§6-5 ④)가 REPEATABLE READ 스냅샷에 갇힌다(함정 ③).
	 */
	@Test
	@DisplayName("PaymentService.grant 에는 @Transactional 이 없다 — 있으면 함정 ③·④")
	void grant_에는_트랜잭션이_없다() throws Exception {
		Method grant = PaymentService.class.getDeclaredMethod("grant", UserId.class, String.class);

		assertThat(grant.getAnnotation(Transactional.class))
			.as("payment-design.md §6-2 — 토스 왕복은 트랜잭션 밖이다(§2-1 쟁점 2)")
			.isNull();
		assertThat(grant.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
		assertThat(PaymentService.class.getAnnotation(Transactional.class))
			.as("클래스 레벨 @Transactional 도 grant 에 그대로 걸린다")
			.isNull();
		assertThat(PaymentService.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}

	/** §6-5 — 주문 원장 + 잔량/구독은 반드시 한 REQUIRES_NEW 트랜잭션이다. */
	@Test
	@DisplayName("GrantWriter.grant 는 @Transactional(REQUIRES_NEW) 다")
	void grantWriter_는_REQUIRES_NEW_다() throws Exception {
		Method grant = GrantWriter.class.getDeclaredMethod("grant",
			UserId.class, String.class, String.class, ProductType.class, java.time.LocalDateTime.class);

		Transactional transactional = grant.getAnnotation(Transactional.class);

		assertThat(transactional)
			.as("§6-5 — UNIQUE(order_id) 판정과 잔량 증가가 같은 트랜잭션이어야 C1 이 막힌다(§6-2 함정 ①)")
			.isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	/**
	 * §6-5 · §2-1 쟁점 1 — {@code GrantWriter} 는 {@code AuthService} 를 주입받지 않는다.
	 * {@code register} 를 REQUIRES_NEW 안에서 부르면 auth-design §6-4 의 전제(트랜잭션 밖 호출)가
	 * 깨져 <b>함정 ④가 auth 쪽에서 되살아난다.</b> auth-design §6-5 재검토 조항이 이 단언을
	 * 감시자로 지목했다(payment-design §7).
	 */
	@Test
	@DisplayName("GrantWriter 는 AuthService 를 주입받지 않는다 — REQUIRES_NEW 안의 register 호출 차단")
	void grantWriter_는_auth_를_모른다() {
		assertThat(Arrays.stream(GrantWriter.class.getDeclaredFields()).map(Field::getType))
			.as("§6-5 — UserId 해석은 컨트롤러에서 끝났고 여기는 이미 해석된 값만 받는다")
			.doesNotContain(AuthService.class);
	}

	/** §4 — 웹훅 반영·recheck 반영 쓰기는 트랜잭션 안이다(별도 빈이라 프록시를 거친다). */
	@Test
	@DisplayName("SubscriptionApplyWriter 의 apply·applyFromClient 는 @Transactional 이다")
	void applyWriter_는_트랜잭션_안이다() throws Exception {
		Method apply = SubscriptionApplyWriter.class.getDeclaredMethod("apply",
			Long.class, kang20.ytcreator.payment.dto.WebhookEvent.class);
		Method applyFromClient = SubscriptionApplyWriter.class.getDeclaredMethod("applyFromClient",
			Long.class, kang20.ytcreator.payment.internal.entity.SubscriptionStatus.class,
			java.time.LocalDateTime.class, boolean.class);

		assertThat(apply.getAnnotation(Transactional.class)).isNotNull();
		assertThat(applyFromClient.getAnnotation(Transactional.class)).isNotNull();
	}

	/**
	 * §6-5 급소 — 쓰기는 <b>다른 빈</b>이어야 한다. {@code PaymentService} 안에서 자기 메서드를
	 * 부르면 프록시를 안 거쳐 REQUIRES_NEW 가 무효가 되고 UNIQUE 위반이 호출자를 오염시킨다(함정 ②).
	 */
	@Test
	@DisplayName("PaymentService 는 쓰기를 GrantWriter·SubscriptionApplyWriter 에 위임한다")
	void 쓰기는_별도_빈에_위임한다() {
		assertThat(Arrays.stream(PaymentService.class.getDeclaredFields()).map(Field::getType))
			.as("별도 빈을 주입받아야 프록시를 거친다(§6-5)")
			.contains(GrantWriter.class, SubscriptionApplyWriter.class);
	}

	/**
	 * §2-2 — 집계는 합치기만 한다. {@code @Transactional} 을 붙이는 순간 {@code register} 가
	 * 트랜잭션 안에서 불려 auth 함정 ④가 되살아난다. 컨트롤러(payment)도 같은 전제다(§2-1 쟁점 1).
	 */
	@Test
	@DisplayName("BootstrapController·PaymentController 에는 @Transactional 이 없다")
	void 컨트롤러에는_트랜잭션이_없다() {
		for (Class<?> controller : new Class<?>[] {BootstrapController.class, PaymentController.class}) {
			assertThat(controller.getAnnotation(Transactional.class))
				.as("%s — register 는 트랜잭션 밖 호출이 전제다(auth-design §6-4)", controller.getSimpleName())
				.isNull();
			assertThat(controller.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
			assertThat(Arrays.stream(controller.getDeclaredMethods()))
				.allSatisfy(method -> {
					assertThat(method.getAnnotation(Transactional.class)).isNull();
					assertThat(method.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
				});
		}
	}
}
