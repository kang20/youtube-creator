package kang20.ytcreator.payment;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import kang20.ytcreator.payment.internal.entity.CreditBalance;
import kang20.ytcreator.payment.internal.entity.PaymentOrder;
import kang20.ytcreator.payment.internal.entity.Subscription;
import kang20.ytcreator.payment.internal.entity.UsageTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;

/**
 * payment-design.md §10 {@code PaymentModuleBoundaryTest} — {@code ModularityTest.verify()} 가
 * <b>못 잡는</b> 불변식 4개를 고정한다.
 *
 * <p>ⓐ allowedDependencies 목록 자체(적어 넣으면 verify 는 정상으로 본다 — auth 선례, 부호 반대)
 * ⓑ 수동 DDL ↔ 엔티티 매핑(§3-1 — validate 는 배포 시점에야 터진다)
 * ⓒ {@code subtitle}·{@code auth/internal} 미참조(§2-1)
 * ⓓ 엔티티에 익명키 파생 컬럼 없음(§2-1 쟁점 1 의 감시자).
 */
class PaymentModuleBoundaryTest {

	private static final Path PAYMENT_SOURCES = Path.of("src/main/java/kang20/ytcreator/payment");
	private static final Path BOOTSTRAP_SOURCES = Path.of("src/main/java/kang20/ytcreator/bootstrap");
	private static final Path DDL = Path.of("deploy/sql/payment-v1.sql");

	private static final List<Class<?>> ENTITIES =
		List.of(PaymentOrder.class, CreditBalance.class, Subscription.class, UsageTicket.class);

	/**
	 * §2-1 쟁점 1 — payment 의 허용 의존은 <b>모듈 축으로 정확히 {@code {shared, auth}}</b> 다.
	 * ⚠️ 여기 {@code subtitle} 이나 {@code bootstrap} 을 적어 넣으면 {@code verify()} 는 그 의존을
	 * <b>정상</b>으로 본다(auth-design §10 선례) — 목록 자체를 고정해야 순환의 씨앗이 안 생긴다.
	 *
	 * <p>named interface 표기({@code "auth :: dto"} 류)는 모듈 축이 같으므로 허용한다 —
	 * 설계서 §10 ⓐ가 막는 것은 "다른 모듈"이지 같은 모듈의 인터페이스 세분화가 아니다.
	 */
	@Test
	@DisplayName("payment 의 허용 의존은 모듈 축으로 shared·auth 뿐이다")
	void 허용_의존은_shared_auth_뿐이다() {
		ApplicationModule module = PaymentReaderPort.class.getPackage().getAnnotation(ApplicationModule.class);

		assertThat(module).isNotNull();
		assertThat(module.displayName()).isEqualTo("결제·이용권");
		assertThat(moduleAxis(module.allowedDependencies()))
			.as("payment-design.md §4 — 다른 모듈이 끼는 순간 §2-1 쟁점 4 의 순환 위험이 생긴다")
			.containsExactlyInAnyOrder("shared", "auth");
	}

	/** §2-2 — bootstrap 은 집계 전용. 허용 의존은 모듈 축으로 {@code {shared, auth, payment}} 단방향이다. */
	@Test
	@DisplayName("bootstrap 의 허용 의존은 모듈 축으로 shared·auth·payment 뿐이다")
	void bootstrap_허용_의존() {
		ApplicationModule module = kang20.ytcreator.bootstrap.BootstrapController.class
			.getPackage().getAnnotation(ApplicationModule.class);

		assertThat(module).isNotNull();
		assertThat(moduleAxis(module.allowedDependencies()))
			.containsExactlyInAnyOrder("shared", "auth", "payment");
	}

	/** {@code "auth :: dto"} → {@code "auth"} — named interface 표기를 모듈명으로 접는다. */
	private static java.util.Set<String> moduleAxis(String[] allowedDependencies) {
		return java.util.Arrays.stream(allowedDependencies)
			.map(dependency -> dependency.split("::")[0].trim())
			.collect(java.util.stream.Collectors.toSet());
	}

	/**
	 * §2-1 — payment 는 {@code auth/internal} 을 참조할 수 없고(노출은 {@code UserId} 뿐),
	 * {@code subtitle} 을 <b>영원히</b> 모른다(§2 — 되돌림은 subtitle 이 release 를 호출).
	 * 컴파일 의존을 소스 import 수준에서 고정한다.
	 */
	@Test
	@DisplayName("payment 소스는 auth.internal 과 subtitle 을 참조하지 않는다")
	void 금지된_참조가_없다() throws IOException {
		try (Stream<Path> files = Files.walk(PAYMENT_SOURCES)) {
			List<Path> violations = files
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> {
					String source = read(path);
					return source.contains("kang20.ytcreator.auth.internal")
						|| source.contains("kang20.ytcreator.subtitle");
				})
				.toList();

			assertThat(violations)
				.as("payment-design.md §2-1 — auth 노출 타입은 UserId·UserIdJavaType·AuthService 뿐이고,"
					+ " subtitle 은 payment 를 아는 쪽이지 payment 가 아는 쪽이 아니다")
				.isEmpty();
		}
	}

	/**
	 * §2-1 쟁점 1 감시자 — payment 는 익명키를 <b>저장·식별에 쓰지 않는다.</b> 엔티티에 익명키
	 * 원문·해시 필드가 생기는 순간 "해시가 테이블 4개에 복제된다"는 기각 사유가 되살아난다.
	 * 익명키가 payment 에 들어오는 지점은 컨트롤러 한 곳(즉시 {@code UserId} 해석)뿐이다.
	 */
	@Test
	@DisplayName("payment 엔티티에는 익명키 원문·해시 컬럼이 없다")
	void 엔티티에_익명키_파생값이_없다() {
		for (Class<?> entity : ENTITIES) {
			assertThat(Arrays.stream(entity.getDeclaredFields()))
				.as("%s — §2-1 쟁점 1: 사용자 식별은 UserId 값 컬럼 하나뿐이다", entity.getSimpleName())
				.allSatisfy(field -> {
					assertThat(field.getName().toLowerCase(Locale.ROOT))
						.doesNotContain("anonymous")
						.doesNotContain("anonkey")
						.doesNotContain("hash");
					Column column = field.getAnnotation(Column.class);
					if (column != null) {
						assertThat(column.name().toLowerCase(Locale.ROOT))
							.doesNotContain("anonymous")
							.doesNotContain("hash");
					}
				});
		}
	}

	/**
	 * §2-1 쟁점 1 — 익명키 수신은 컨트롤러 한 곳뿐이다. 서비스·internal 로 원문이 들어오면
	 * "즉시 해석해 버린다"는 경계가 무너진다. bootstrap 도 같은 규칙이다(§2-2).
	 */
	@Test
	@DisplayName("익명키 타입(AnonymousAuthentication)을 다루는 payment 소스는 컨트롤러뿐이다")
	void 익명키는_컨트롤러에서_끝난다() throws IOException {
		try (Stream<Path> files = Stream.concat(Files.walk(PAYMENT_SOURCES), Files.walk(BOOTSTRAP_SOURCES))) {
			List<String> holders = files
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> read(path).contains("AnonymousAuthentication"))
				.map(path -> path.getFileName().toString())
				.toList();

			assertThat(holders)
				.containsExactlyInAnyOrder("PaymentController.java", "BootstrapController.java");
		}
	}

	/**
	 * §3-1 — 운영은 {@code ddl-auto: validate} 라 스키마가 배포로 만들어지지 않는다.
	 * 엔티티 매핑이 바뀌면 DDL 파일이 따라오는지를 고정한다(auth 가 두 번 밟은 함정 —
	 * 테이블명 대소문자 · CHAR/VARCHAR).
	 */
	@Test
	@DisplayName("수동 DDL 이 엔티티 매핑과 어긋나지 않는다 — validate 는 배포 시점에야 터진다")
	void 수동_DDL_이_매핑과_일치한다() throws IOException {
		// 주석(-- ...)은 지우고 실제 DDL 문장만 본다 — 주석이 "CHECK 를 걸지 마라" 같은 금지어를 담는다
		String ddl = Files.readString(DDL, StandardCharsets.UTF_8)
			.replaceAll("(?m)--.*$", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", " ");

		// 테이블 4개 — 이름은 소문자(§3-1 — 리눅스 MySQL 대소문자 함정)
		for (Class<?> entity : ENTITIES) {
			String tableName = entity.getAnnotation(Table.class).name();
			assertThat(tableName).isEqualTo(tableName.toLowerCase(Locale.ROOT));
			assertThat(ddl).contains("create table " + tableName);
		}

		// 🔴 멱등(U3)·경쟁(C1·C2)·단일 구독(§3)의 근거가 되는 UNIQUE 제약 — 빠지면 이중 지급이 난다
		assertThat(ddl)
			.contains("constraint uk_payment_orders_order_id unique (order_id)")
			.contains("constraint uk_credit_balances_user_id unique (user_id)")
			.contains("constraint uk_subscriptions_order_id unique (order_id)")
			.contains("constraint uk_subscriptions_user_id unique (user_id)");

		// 조회 경로 인덱스(§3)
		assertThat(ddl)
			.contains("create index ix_payment_orders_user_id on payment_orders (user_id)")
			.contains("create index ix_usage_tickets_user_id_status on usage_tickets (user_id, status)");

		// 타입 ID 는 자바 표현일 뿐 — DDL 은 원시 BIGINT 다(§3-1)
		assertThat(ddl).contains("user_id bigint");

		// §3-1 — balance 에 CHECK 를 걸지 않는다(잔량 부족 판정은 조건부 UPDATE 0행 → PAY_001 하나로)
		assertThat(ddl).doesNotContain("check");

		// 물리 FK 금지(§3-1) — 참조는 주석으로만
		assertThat(ddl).doesNotContain("foreign key");
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
