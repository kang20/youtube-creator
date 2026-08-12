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

/**
 * U4 의 구조적 부분과 auth-design.md §2-2 불변식.
 *
 * <p>{@code ModularityTest} 의 {@code verify()} 로는 잡히지 <b>않는</b> 두 가지를 여기서 막는다.
 *
 * <ul>
 *   <li>auth 에 컨트롤러가 <b>불어나는</b> 것 — auth.md §5-3 (v4 번복) 은 refresh <b>하나만</b>
 *       허용한다. 컨트롤러가 늘어도 모듈 경계 검증은 통과한다.</li>
 *   <li>{@code allowedDependencies} 에 {@code subscription} 이 추가되는 것 — 목록에 적어 넣으면
 *       {@code verify()} 는 그 의존을 <b>정상</b>으로 본다. auth.md §4-7 이 막으려던 순환의 씨앗이
 *       조용히 통과한다.</li>
 * </ul>
 */
class AuthModuleBoundaryTest {

	private static final String AUTH_PACKAGE = "kang20.ytcreator.auth";

	/**
	 * U4 · auth.md §5-3 <b>(v4 번복)</b> — auth 의 HTTP 엔드포인트는 {@code AuthTokenController}
	 * (refresh) <b>하나뿐</b>이다. "토큰 갱신은 합칠 곳이 없다"가 번복의 전부이고, 진입 응답 조립은
	 * 여전히 집계 모듈 {@code bootstrap} 몫이다(§4-7). 여기 두 번째 컨트롤러가 생기면
	 * "auth 가 서서히 홈 화면 API 가 된다"는 §4-7 의 우려가 현실이 된다.
	 */
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

	/**
	 * auth-design.md §2-1 쟁점 3 · §2-2 — auth 의 허용 의존은 {@code shared} 하나뿐이다.
	 * ⚠️ auth 는 subscription 을 <b>영원히</b> 참조하지 않는다(auth.md §4-7).
	 */
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

	/**
	 * auth-design.md §3 · §3-2 (v2) — <b>컬럼 길이는 해시 출력 길이와 같아야 한다.</b>
	 *
	 * <p>라운드 2 의 이 테스트는 "컬럼 길이 == {@code AnonymousKeyFormat.MAX_LENGTH}" 였다.
	 * 해시 저장(§3-2)으로 <b>그 전제는 사라졌다</b> — 저장 값은 원문이 아니라 SHA-256 hex 이고,
	 * §12-2(v2)가 *"저장 길이와 입력 검증은 별개 축이라 같이 움직이지 않는다"* 로 명시했다.
	 *
	 * <p>그래서 같은 종류의 사고(저장이 컬럼을 넘겨 삽입이 터지는 것)를 막는 불변식을
	 * <b>새 축으로 옮긴다</b>: 해시 알고리즘이 바뀌면(SHA-512 등) 출력이 128자가 되어
	 * 컬럼(64)을 넘고 <b>모든 등록이 500 이 된다.</b> 여기서 먼저 걸려야 한다.
	 */
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

	/**
	 * auth-design.md §12-2 (v2) 말미 — <b>입력 검증은 저장 결정을 따라가지 않는다.</b>
	 *
	 * <p>{@code AnonymousKeyFormat.isValid} 는 <b>들어오는 원문</b>의 방어선이고(U5),
	 * 컬럼 길이 64 는 <b>저장 값</b>의 결정이다. 해시 저장을 보고 "이제 64자만 받으면 되겠네" 하고
	 * 입력 상한을 해시 길이로 줄이면 <b>정상 익명키가 전부 AUTH_002 로 거부된다.</b>
	 * (실제 hash 형식은 아직 미확정이다 — auth.md 🔶-3 은 v2 에서도 열려 있다)
	 */
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

	/**
	 * auth-design.md §3-1 — 운영 스키마는 배포로 만들어지지 않는다(`ddl-auto: validate`).
	 * 수동 DDL 이 매핑과 어긋나면 <b>기동 자체가 실패</b>하고, 그건 배포 시점에야 드러난다.
	 *
	 * <p>이 도메인은 그 함정을 이미 두 번 밟았다 — 테이블명 대소문자(§3-1)와
	 * <b>{@code CHAR(64)} vs {@code VARCHAR(64)}</b>(라운드 3 실측). 둘 다 로컬 H2 에서는 안 드러난다.
	 * 그래서 엔티티 매핑이 바뀌면 DDL 파일이 따라오는지를 여기서 고정한다.
	 */
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

	/**
	 * (v4) auth-design.md §14-2 — {@code refresh_tokens} 수동 DDL(auth-v2.sql) ↔ 엔티티 매핑.
	 * users 가 두 번 밟은 함정(테이블명 대소문자 · CHAR/VARCHAR)과 같은 축이라 같은 방식으로 고정한다.
	 * U10(원문 비저장)의 물리 근거 — 컬럼 길이가 해시(64)에 묶여 있으면 원문(가변 길이)은 들어갈 수 없다.
	 */
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
