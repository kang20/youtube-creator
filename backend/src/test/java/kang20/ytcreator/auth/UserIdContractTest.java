package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code UserId} 계약 단위 (payment-design.md §10 "UserId·UsageTicketId 계약").
 *
 * <p>덮는 것: 값 동등성 · <b>final 선언</b>(ValueObject strict equals 전제) ·
 * <b>public (Long) 생성자</b>(리플렉션 계약 — architecture.md 함정 표) · toString 형식.
 */
class UserIdContractTest {

	/** ValueObject 는 strict getClass 비교라 하위 타입이 끼면 동등성이 비대칭으로 깨진다 */
	@Test
	@DisplayName("UserId 는 final 이다 — 하위 타입 금지")
	void final_선언() {
		assertThat(Modifier.isFinal(UserId.class.getModifiers())).isTrue();
	}

	/**
	 * 리플렉션 계약 — {@code UserIdJavaType.wrap/fromString} 과 Hibernate 하이드레이션이
	 * 박싱 {@code Long} 1개짜리 public 생성자를 그대로 탄다. 시그니처가 바뀌면 컴파일은 되고
	 * <b>런타임에만</b> 죽는다.
	 */
	@Test
	@DisplayName("박싱 Long 1개짜리 public 생성자가 있다 — Hibernate 리플렉션 계약")
	void 리플렉션_생성자_계약() throws Exception {
		Constructor<UserId> constructor = UserId.class.getDeclaredConstructor(Long.class);

		assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
		assertThat(constructor.newInstance(5L)).isEqualTo(new UserId(5L));
	}

	/** §2-1 쟁점 1 — 같은 사용자면 같다. 값 비교가 안 되면 멱등·소유권 판정 전부가 깨진다 */
	@Test
	@DisplayName("같은 값이면 동등하고 다른 값이면 다르다")
	void 값_동등성() {
		assertThat(new UserId(1L))
			.isEqualTo(new UserId(1L))
			.hasSameHashCodeAs(new UserId(1L))
			.isNotEqualTo(new UserId(2L));
		assertThat(new UserId(1L).longValue()).isEqualTo(1L);
	}

	/** U14 와 같은 결 — 진단 표기는 "UserId(값)" 뿐이고 다른 정보를 싣지 않는다 */
	@Test
	@DisplayName("toString 은 'UserId(값)' 형식이다")
	void toString_형식() {
		assertThat(new UserId(42L).toString()).isEqualTo("UserId(42)");
	}
}
