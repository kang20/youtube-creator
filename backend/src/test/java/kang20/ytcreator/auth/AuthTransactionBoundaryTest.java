package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import kang20.ytcreator.auth.internal.service.AuthService;
import kang20.ytcreator.auth.internal.service.support.UserWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth-design.md §6-2 함정 ④ · §6-4 <b>회귀 방지</b>.
 *
 * <p>§6-4 가 채택한 구조는 "바깥 트랜잭션 없음 + 삽입만 {@code REQUIRES_NEW}" 다.
 * 이 구조가 깨지는 방식은 둘인데, <b>둘 다 H2 에서는 통과하고 운영 MySQL 에서만 터진다</b> —
 * 즉 기능 테스트로는 잡히지 않는다. 그래서 어노테이션 자체를 단언한다.
 *
 * <ol>
 *   <li>누가 {@code AuthService.register} 에 {@code @Transactional} 을 붙인다 → 함정 ④.
 *       MySQL {@code REPEATABLE READ} 스냅샷이 고정되어 재조회(③)가 경쟁자의 커밋을 보지 못한다.
 *       H2 는 {@code READ COMMITTED} 라 재현되지 않는다.</li>
 *   <li>누가 {@code UserWriter.insert} 를 {@code AuthService} 안으로 합친다 → self-invocation 이라
 *       프록시를 거치지 않아 {@code REQUIRES_NEW} 가 걸리지 않는다 → 함정 ②(rollback-only 오염).</li>
 * </ol>
 */
class AuthTransactionBoundaryTest {

	/**
	 * §6-4 — {@code register} 에 트랜잭션이 <b>없어야</b> 한다. 붙는 순간 함정 ④가 되살아난다.
	 * 클래스 레벨·jakarta 쪽까지 함께 본다(한 곳만 보면 다른 경로로 붙일 수 있다).
	 */
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

	/** §6-4 ② — 삽입만 별도 트랜잭션. {@code REQUIRES_NEW} 여야 실패가 바깥을 오염시키지 않는다. */
	@Test
	@DisplayName("UserWriter.insert 는 @Transactional(REQUIRES_NEW) 다")
	void insert_는_REQUIRES_NEW_다() throws Exception {
		Method insert = UserWriter.class.getDeclaredMethod("insert", String.class);

		Transactional transactional = insert.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	/**
	 * §6-4 "이 설계의 급소" — 삽입은 <b>다른 빈</b>이어야 한다.
	 * {@code AuthService} 안에서 자기 메서드를 부르면 프록시를 안 거쳐 {@code REQUIRES_NEW} 가 무효가 된다.
	 */
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
}
