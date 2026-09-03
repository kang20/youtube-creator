package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import kang20.ytcreator.auth.internal.service.AuthService;
import kang20.ytcreator.auth.internal.service.support.RefreshTokenWriter;
import kang20.ytcreator.auth.internal.service.support.UserWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class AuthTransactionBoundaryTest {

	@Test
	@DisplayName("AuthService.register 에는 @Transactional 이 없다 — 있으면 함정 ④")
	void register_에는_트랜잭션이_없다() throws Exception {
		Method register = AuthService.class.getDeclaredMethod("register", String.class);

		assertThat(register.getAnnotation(Transactional.class))
			.as("메서드에 spring @Transactional 이 붙었다 — auth-design.md §6-4 는 '붙이지 않는다'로 확정했다")
			.isNull();
		assertThat(register.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
		assertThat(AuthService.class.getAnnotation(Transactional.class))
			.as("클래스 레벨 @Transactional 도 메서드에 그대로 걸린다")
			.isNull();
		assertThat(AuthService.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
	}

	@Test
	@DisplayName("UserWriter.insert 는 @Transactional(REQUIRES_NEW) 다")
	void insert_는_REQUIRES_NEW_다() throws Exception {
		Method insert = UserWriter.class.getDeclaredMethod("insert", String.class);

		Transactional transactional = insert.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	@Test
	@DisplayName("AuthService 는 삽입을 직접 하지 않고 UserWriter 를 주입받아 위임한다")
	void 삽입은_별도_빈에_위임한다() {
		assertThat(Arrays.stream(AuthService.class.getDeclaredFields()).map(Field::getType))
			.as("UserWriter 를 필드로 주입받아야 프록시를 거친다")
			.contains(UserWriter.class);

		assertThat(Arrays.stream(AuthService.class.getDeclaredMethods()).map(Method::getName))
			.as("삽입 메서드가 AuthService 로 옮겨오면 self-invocation 이 되어 REQUIRES_NEW 가 죽는다")
			.doesNotContain("insert", "save", "saveAndFlush");

		assertThat(UserWriter.class).isNotEqualTo(AuthService.class);
	}

	@Test
	@DisplayName("AuthService.login·refresh 에는 @Transactional 이 없다 — §14-3 '트랜잭션 없음'")
	void login_refresh_에는_트랜잭션이_없다() throws Exception {
		for (Method method : new Method[] {
				AuthService.class.getDeclaredMethod("login", String.class),
				AuthService.class.getDeclaredMethod("refresh", String.class)}) {
			assertThat(method.getAnnotation(Transactional.class))
				.as("%s — auth-design.md §14-3 은 '트랜잭션 없음'으로 확정했다", method.getName())
				.isNull();
			assertThat(method.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
		}
	}

	@Test
	@DisplayName("RefreshTokenWriter 의 쓰기 메서드는 전부 @Transactional 이다")
	void refresh_writer_쓰기는_전부_트랜잭션이다() throws Exception {
		Method[] writes = {
			RefreshTokenWriter.class.getDeclaredMethod("issue", UserId.class, java.time.LocalDateTime.class),
			RefreshTokenWriter.class.getDeclaredMethod("rotate", String.class, java.time.LocalDateTime.class),
			RefreshTokenWriter.class.getDeclaredMethod("revokeAllByUserId", UserId.class,
				java.time.LocalDateTime.class),
		};
		for (Method method : writes) {
			assertThat(method.getAnnotation(Transactional.class))
				.as("%s — @Modifying JPQL 은 트랜잭션 필수다(auth-design.md §14-2·round-1-dev.md 판단 4)",
					method.getName())
				.isNotNull();
		}

		// 회전도 별도 빈 위임이다 — AuthService 안으로 합치면 self-invocation 으로 TX 가 죽는다(위 ②와 동일)
		assertThat(Arrays.stream(AuthService.class.getDeclaredFields()).map(Field::getType))
			.contains(RefreshTokenWriter.class);
	}
}
