package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import kang20.ytcreator.auth.UserId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code UsageTicketId} 계약 단위 (payment-design.md §10 "UserId·UsageTicketId 계약").
 *
 * <p>{@code UsageTicketId} 는 subtitle 에 노출되는 <b>유일한 payment 식별자</b>다(§3) —
 * {@code commit}/{@code release} 시그니처가 이 타입에 걸려 있다.
 */
class UsageTicketIdContractTest {

	@Test
	@DisplayName("UsageTicketId 는 final 이다 — 하위 타입 금지")
	void final_선언() {
		assertThat(Modifier.isFinal(UsageTicketId.class.getModifiers())).isTrue();
	}

	/** IDENTITY 채번값을 {@code UsageTicketIdJavaType.wrap} 이 리플렉션으로 감싼다 */
	@Test
	@DisplayName("박싱 Long 1개짜리 public 생성자가 있다 — Hibernate 리플렉션 계약")
	void 리플렉션_생성자_계약() throws Exception {
		Constructor<UsageTicketId> constructor = UsageTicketId.class.getDeclaredConstructor(Long.class);

		assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
		assertThat(constructor.newInstance(5L)).isEqualTo(new UsageTicketId(5L));
	}

	@Test
	@DisplayName("같은 값이면 동등하고 다른 값이면 다르다")
	void 값_동등성() {
		assertThat(new UsageTicketId(1L))
			.isEqualTo(new UsageTicketId(1L))
			.hasSameHashCodeAs(new UsageTicketId(1L))
			.isNotEqualTo(new UsageTicketId(2L));
		assertThat(new UsageTicketId(1L).longValue()).isEqualTo(1L);
	}

	/** §2-1 쟁점 1 — ticketId·userId 혼용을 컴파일러와 동등성이 함께 잡는다 */
	@Test
	@DisplayName("같은 값의 UserId 와 동등하지 않다 — 도메인 혼용 차단")
	void 도메인_혼용_차단() {
		assertThat(new UsageTicketId(1L)).isNotEqualTo(new UserId(1L));
	}

	@Test
	@DisplayName("toString 은 'UsageTicketId(값)' 형식이다")
	void toString_형식() {
		assertThat(new UsageTicketId(42L).toString()).isEqualTo("UsageTicketId(42)");
	}
}
