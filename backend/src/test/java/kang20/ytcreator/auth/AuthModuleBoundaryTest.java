package kang20.ytcreator.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import kang20.ytcreator.auth.internal.service.support.AnonymousKeyHasher;
import kang20.ytcreator.auth.internal.entity.User;
import kang20.ytcreator.shared.security.AnonymousKeyFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.modulith.ApplicationModule;
import org.springframework.stereotype.Controller;

class AuthModuleBoundaryTest {

	private static final String AUTH_PACKAGE = "kang20.ytcreator.auth";

	@Test
	@DisplayName("auth 모듈의 HTTP 엔드포인트는 refresh 컨트롤러 하나뿐이다")
	void auth_의_컨트롤러는_refresh_하나뿐이다() {
		ClassPathScanningCandidateComponentProvider scanner =
			new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));   // @RestController 도 메타로 잡힌다

		assertThat(scanner.findCandidateComponents(AUTH_PACKAGE))
			.as("auth.md §5-3 v4 — auth 가 갖는 HTTP 엔드포인트는 POST /api/v1/auth/refresh 하나다")
			.extracting(definition -> definition.getBeanClassName())
			.containsExactly("kang20.ytcreator.auth.internal.handler.inbound.AuthTokenController");
	}

	@Test
	@DisplayName("auth 의 허용 의존은 shared 하나뿐이다 — subscription 이 끼면 실패한다")
	void 허용_의존은_shared_하나뿐이다() {
		ApplicationModule module = AuthPort.class.getPackage().getAnnotation(ApplicationModule.class);

		assertThat(module).isNotNull();
		assertThat(module.displayName()).isEqualTo("인증");
		assertThat(module.allowedDependencies())
			.as("auth.md §4-7 — auth 가 subscription 을 알게 되면 순환의 씨앗이 된다")
			.containsExactly("shared");
	}

	@Test
	@DisplayName("컬럼 길이는 해시 출력 길이와 같다 — 알고리즘을 바꾸면 여기서 먼저 걸린다")
	void 컬럼_길이는_해시_출력_길이와_같다() throws Exception {
		Column column = User.class.getDeclaredField("anonymousKeyHash").getAnnotation(Column.class);

		assertThat(column).isNotNull();
		assertThat(column.name()).isEqualTo("anonymous_key_hash");
		assertThat(column.length())
			.as("auth-design.md §3-2 — 저장 길이는 해시 출력 길이(SHA-256 hex = 64)에 묶인다")
			.isEqualTo(new AnonymousKeyHasher().hash("아무 익명키").length());
	}

	@Test
	@DisplayName("입력 형식 검증은 여전히 원문 기준이다 — 해시 길이로 좁히면 안 된다")
	void 입력_검증은_저장_길이를_따라가지_않는다() throws Exception {
		int storedLength = User.class.getDeclaredField("anonymousKeyHash")
			.getAnnotation(Column.class).length();

		assertThat(AnonymousKeyFormat.MAX_LENGTH)
			.as("두 값은 다른 축이다 — 같아지면 둘 중 하나가 잘못 따라간 것이다")
			.isNotEqualTo(storedLength);

		assertThat(AnonymousKeyFormat.isValid("a".repeat(storedLength + 1)))
			.as("해시 길이보다 긴 원문도 통과해야 한다 — 아니면 정상 사용자가 AUTH_002 를 받는다")
			.isTrue();
	}

	@Test
	@DisplayName("수동 DDL 이 엔티티 매핑과 어긋나지 않는다 — validate 는 배포 시점에야 터진다")
	void 수동_DDL_이_매핑과_일치한다() throws Exception {
		Column column = User.class.getDeclaredField("anonymousKeyHash").getAnnotation(Column.class);
		String ddl = Files.readString(Path.of("deploy/sql/auth-v1.sql"), StandardCharsets.UTF_8)
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", " ");

		assertThat(ddl).contains("create table users");
		assertThat(ddl)
			.as("§3-1 실측 — CHAR 로 쓰면 'wrong column type ... expecting varchar' 로 기동이 실패한다")
			.contains(column.name() + " varchar(" + column.length() + ")");
		assertThat(ddl)
			.as("UNIQUE 제약이 멱등(U2)의 유일한 근거다 — 이름까지 매핑과 맞춰 둔다")
			.contains(User.class.getAnnotation(Table.class).uniqueConstraints()[0].name().toLowerCase(Locale.ROOT));
		assertThat(ddl).contains("unique (" + column.name() + ")");
	}

	@Test
	@DisplayName("refresh_tokens 수동 DDL 이 엔티티 매핑과 어긋나지 않는다")
	void refresh_tokens_DDL_이_매핑과_일치한다() throws Exception {
		Column tokenHash = kang20.ytcreator.auth.internal.entity.RefreshToken.class
			.getDeclaredField("tokenHash").getAnnotation(Column.class);
		String ddl = Files.readString(Path.of("deploy/sql/auth-v2.sql"), StandardCharsets.UTF_8)
			.replaceAll("(?m)--.*$", "")   // 주석 제거 — 주석이 "원문을 넣지 마라" 같은 금지어를 담는다
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", " ");

		assertThat(ddl).contains("create table refresh_tokens");
		assertThat(ddl)
			.as("§3-1 실측 — CHAR 로 쓰면 validate 가 기동을 거부한다. 길이는 SHA-256 hex 고정(U10)")
			.contains(tokenHash.name() + " varchar(" + tokenHash.length() + ")");
		assertThat(ddl)
			.as("UNIQUE 가 해시 조회 키(§14-2)의 근거다")
			.contains("constraint uk_refresh_tokens_token_hash unique (token_hash)");
		assertThat(ddl)
			.as("전체 폐기(U9 — UPDATE WHERE user_id)가 타는 인덱스(round-1-dev.md 판단 12)")
			.contains("create index ix_refresh_tokens_user_id on refresh_tokens (user_id)");
		// 물리 FK 금지 — architecture.md "타입화된 기본키" 기본 정책(payment-v1.sql 과 같은 규율)
		assertThat(ddl).doesNotContain("foreign key");
	}
}
