package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserIdContractTest {

	@Test
	@DisplayName("UserId 는 final 이다 — 하위 타입 금지")
	void final_선언() {
		assertThat(Modifier.isFinal(UserId.class.getModifiers())).isTrue();
	}

	@Test
	@DisplayName("박싱 Long 1개짜리 public 생성자가 있다 — Hibernate 리플렉션 계약")
	void 리플렉션_생성자_계약() throws Exception {
		Constructor<UserId> constructor = UserId.class.getDeclaredConstructor(Long.class);

		assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
		assertThat(constructor.newInstance(5L)).isEqualTo(new UserId(5L));
	}

	@Test
	@DisplayName("같은 값이면 동등하고 다른 값이면 다르다")
	void 값_동등성() {
		assertThat(new UserId(1L))
			.isEqualTo(new UserId(1L))
			.hasSameHashCodeAs(new UserId(1L))
			.isNotEqualTo(new UserId(2L));
		assertThat(new UserId(1L).longValue()).isEqualTo(1L);
	}

	@Test
	@DisplayName("toString 은 'UserId(값)' 형식이다")
	void toString_형식() {
		assertThat(new UserId(42L).toString()).isEqualTo("UserId(42)");
	}
}
