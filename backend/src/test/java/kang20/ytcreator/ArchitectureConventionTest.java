package kang20.ytcreator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Port·Service·Support 규약</b>의 강제 테스트 — {@code ModularityTest.verify()}(모듈 경계) 위에
 * 얹는 <b>모듈 내부 레이아웃</b> 규율이다. 근거: architecture.md "Port·Service·Support 규약"
 * (2026-08-12 사용자 결정).
 *
 * <p>Modulith 는 "다른 모듈이 {@code internal} 을 참조하는가"만 본다. 그 아래의
 * <b>같은 모듈 안 레이아웃</b>은 검증하지 못한다 — 이 테스트가 그 층을 맡는다:
 *
 * <ol>
 *   <li><b>R1</b> 모듈 루트의 공개 타입은 <b>다른 모듈이 실제로 참조한다</b>. 안 쓰이면 {@code internal} 로
 *       내린다. {@code *Port} 가 살 수 있는 자리는 모듈 루트와 {@code internal/port} 둘뿐이다.</li>
 *   <li><b>R2</b> {@code internal/service} 직속은 <b>전부 {@code *Service}</b> 이고, 각각 {@code *Port} 를 구현한다.
 *       <b>개수는 제한하지 않는다</b> — 책임이 갈리면 Service 를 나눈다.</li>
 *   <li><b>R3</b> {@code internal/service/support} 의 타입은 전부 {@code @Support} 이고, {@code @Support} 는 거기에만 있다.</li>
 *   <li><b>R4</b> {@code @Support} 는 <b>같은 모듈의 {@code *Service} 중 하나 + 모듈 루트의 게이트 부품(필터·리졸버)만</b>
 *       참조한다 — 게이트 예외의 근거는 auth-design.md §14-1.</li>
 *   <li><b>R5</b> {@code internal/handler}(컨트롤러 등)는 {@code internal/service} 를 직접 참조하지 않는다 — 포트로만 부른다.</li>
 *   <li><b>R6</b> 구체 {@code *Service} 는 자기 패키지 밖 어디서도 참조되지 않는다 — 밖은 포트로만 부른다.</li>
 *   <li><b>R7</b> {@code @Support} 에 {@code @Component} 를 함께 붙이지 않는다 — 메타 {@code @Component} 라 중복이다.</li>
 * </ol>
 *
 * <p><b>도메인 모듈 = {@code internal/service} 를 가진 모듈</b>(auth·payment). {@code shared}·{@code config}
 * (OPEN 공용)과 {@code bootstrap}(저장소 없는 집계 어댑터)은 규약 대상이 아니다 — 사용자 결정.
 *
 * <p>텍스트(import) 기준이라 <b>같은 패키지 안 참조</b>는 못 잡는다({@code service/support} 내부끼리) —
 * 그건 설계상 존재하지 않아야 하고, 리뷰가 함께 본다. 크로스 패키지 참조는 import 가 필수라 전부 잡힌다.
 */
class ArchitectureConventionTest {

	private static final Path SRC = Path.of("src/main/java/kang20/ytcreator");

	/** annotation 사용 지점만 잡는다 — javadoc 의 {@code @Support}(줄이 {@code *} 로 시작)나 정의부는 제외. */
	private static final Pattern SUPPORT_USAGE = Pattern.compile("(?m)^\\s*@Support\\b");

	/** 같은 논리로 {@code @Component} 사용 지점만 잡는다 — javadoc 언급은 제외. */
	private static final Pattern COMPONENT_USAGE = Pattern.compile("(?m)^\\s*@Component\\b");

	// ── R1 ─────────────────────────────────────────────────────────────
	/**
	 * <b>규약 갱신 이력 (2026-08-15 사용자 결정)</b>: 구 R1 은 "루트에 {@code *Port} 가 있다"였는데,
	 * <b>있는지</b>만 보고 <b>쓰이는지</b>는 보지 않아 소비자 없는 포트가 공개 표면에 쌓였다 — 5개 포트 중
	 * 밖에서 실제로 불리는 것은 {@code AuthPort} 하나뿐이었다. 규칙을 뒤집어 <b>루트에 있으려면 밖에서
	 * 쓰여야 한다</b>로 세운다. 포트 존재 자체는 R2(Service 가 {@code *Port} 를 구현한다)가 이미 강제한다.
	 *
	 * <p>기준은 <b>{@code src/main} 참조뿐</b>이다. 흐름 테스트가 다른 모듈의 포트를 구동하는 것은
	 * 컨트롤러·리스너 대역이지 프로덕션 계약이 아니다 — 테스트를 세면 "안 쓰는데 공개"가 그대로 남는다.
	 */
	@Test
	@DisplayName("R1 — 모듈 루트의 공개 타입은 다른 모듈이 실제로 참조한다")
	void 루트_공개_타입은_밖에서_쓰인다() throws IOException {
		List<Path> mainFiles = allMainJava();
		for (String module : domainModules()) {
			String modulePkg = "kang20.ytcreator." + module;
			for (String type : rootTypeNames(module)) {
				String importLine = "import " + modulePkg + "." + type + ";";
				boolean usedOutside = mainFiles.stream()
					.filter(f -> !packageName(f).startsWith(modulePkg))
					.anyMatch(f -> read(f).contains(importLine));
				assertThat(usedOutside)
					.as("%s — 모듈 루트는 공개 표면이다. %s 를 밖에서 아무도 쓰지 않는다면 internal 로 내려라"
							+ "(포트라면 internal/port)", module, type)
					.isTrue();
			}
		}

		// *Port 가 살 수 있는 자리는 모듈 루트와 internal/port 둘뿐이다 — 구현 옆에 두면 R5 가 무너진다
		for (Path file : mainFiles) {
			String type = typeName(file);
			if (type == null || !type.endsWith("Port")) {
				continue;
			}
			String pkg = packageName(file);
			assertThat(pkg.matches("kang20\\.ytcreator\\.\\w+(\\.internal\\.port)?"))
				.as("%s — *Port 는 모듈 루트(공개) 또는 internal/port(비공개)에만 둔다. 지금은 %s", type, pkg)
				.isTrue();
		}
	}

	// ── R2 ─────────────────────────────────────────────────────────────
	/**
	 * <b>규약 갱신 이력 (2026-08-18 사용자 결정)</b>: 구 R2 는 {@code hasSize(1)} 로 <b>Service 를 하나로</b>
	 * 못 박았다. 그래서 책임이 갈리는 흐름(예: 웹훅 진위 검증 대 상태 반영)을 나누려 하면 규약이 먼저
	 * 막았고, 대안은 support 로 내리는 것뿐인데 support 는 R4 때문에 컨트롤러가 못 부른다 — <b>쪼갤 자리가
	 * 아예 없는</b> 상태였다. 개수 제한을 걷어내고 <b>"직속은 전부 {@code *Service} 이고 각각 포트를
	 * 구현한다"</b>만 남긴다. 오케스트레이션의 주인이 Service 라는 성질은 R4·R5·R6 가 그대로 지킨다.
	 */
	@Test
	@DisplayName("R2 — internal/service 직속은 전부 *Service 이고 각각 *Port 를 구현한다")
	void service_는_전부_포트를_구현한다() throws IOException {
		for (String module : domainModules()) {
			List<Path> direct = serviceFiles(module);

			assertThat(direct)
				.as("%s — service 직속에 오케스트레이터(*Service)가 최소 하나는 있어야 한다", module)
				.isNotEmpty();
			for (Path service : direct) {
				assertThat(service.getFileName().toString())
					.as("%s — service 직속 파일은 전부 *Service 다(부품은 support/ 로 내린다). %s",
						module, service.getFileName())
					.endsWith("Service.java");
				assertThat(read(service))
					.as("%s — %s 는 포트를 구현해야 한다(implements *Port)", module, service.getFileName())
					.containsPattern("implements[\\s\\S]*?Port");
			}
		}
	}

	// ── R3 ─────────────────────────────────────────────────────────────
	@Test
	@DisplayName("R3 — support 타입은 전부 @Support 이고, @Support 는 service/support 에만 있다")
	void support_어노테이션은_service_support_에만() throws IOException {
		// ⓐ service/support 의 타입은 전부 @Support
		for (String module : domainModules()) {
			Path supportDir = SRC.resolve(module).resolve("internal/service/support");
			for (Path file : javaFiles(supportDir)) {
				if (file.getFileName().toString().equals("package-info.java")) {
					continue;
				}
				assertThat(SUPPORT_USAGE.matcher(read(file)).find())
					.as("%s — service/support 의 %s 는 @Support 여야 한다", module, file.getFileName())
					.isTrue();
			}
		}

		// ⓑ @Support 가 붙은 파일은 반드시 어떤 모듈의 internal/service/support 아래에 있다
		for (Path file : allMainJava()) {
			if (SUPPORT_USAGE.matcher(read(file)).find()) {
				assertThat(file.toString().replace('\\', '/'))
					.as("@Support 는 도메인 모듈의 internal/service/support 에만 붙는다 — %s", file)
					.contains("/internal/service/support/");
			}
		}
	}

	// ── R4 ─────────────────────────────────────────────────────────────
	/**
	 * <b>규약 갱신 이력 (blockers B1 · <a href="../../docs/domain/auth-design.md">auth-design.md §14-1</a>)</b>:
	 * v4 JWT 전환에서 게이트 부품({@code JwtAuthenticationFilter} 등)이 auth <b>모듈 루트</b>로 왔다 —
	 * 게이트 부품은 HTTP 어댑터가 아니라 <b>다른 모듈(config)이 조립하는 공개 계약</b>이고,
	 * 검증 부품({@code JwtSupport} — @Support)을 부르는 것이 그 존재 이유다. 그래서 §14-1 갱신안대로
	 * 허용 참조자를 <i>"같은 모듈의 *Service + 모듈 루트의 게이트 부품(필터·리졸버)"</i> 로 확장한다.
	 * 게이트 부품의 판별은 <b>모듈 루트 패키지 + 이름 접미사(Filter·Resolver)</b>다 — internal 의
	 * 필터·리졸버는 여전히 걸린다(루트 = config 가 조립하는 공개 계약이라는 §14-1 전제가 판별 기준).
	 */
	@Test
	@DisplayName("R4 — @Support 는 같은 모듈의 *Service 와 모듈 루트 게이트 부품(필터·리졸버)만 참조한다")
	void support_는_service_만_참조한다() throws IOException {
		List<Path> mainFiles = allMainJava();
		for (String module : domainModules()) {
			List<String> serviceNames = serviceSimpleNames(module);   // 예: [PaymentService]
			String modulePkg = "kang20.ytcreator." + module;
			String supportPkg = modulePkg + ".internal.service.support";
			Path supportDir = SRC.resolve(module).resolve("internal/service/support");

			for (Path support : javaFiles(supportDir)) {
				String type = typeName(support);
				if (type == null) {
					continue;   // package-info
				}
				String importLine = "import " + supportPkg + "." + type + ";";
				for (Path other : mainFiles) {
					if (!read(other).contains(importLine)) {
						continue;
					}
					String referrer = typeName(other);
					boolean isModuleService = serviceNames.contains(referrer);
					// auth-design.md §14-1 — 모듈 루트의 게이트 부품(필터·리졸버)은 허용 참조자다
					boolean isRootGatePart = packageName(other).equals(modulePkg)
						&& (referrer.endsWith("Filter") || referrer.endsWith("Resolver"));
					assertThat(isModuleService || isRootGatePart)
						.as("%s — support(%s)는 같은 모듈의 *Service%s 또는 모듈 루트 게이트 부품(필터·리졸버)만"
								+ " 참조할 수 있다(auth-design.md §14-1). %s 가 참조한다",
							module, type, serviceNames, other.getFileName())
						.isTrue();
				}
			}
		}
	}

	// ── R5 ─────────────────────────────────────────────────────────────
	@Test
	@DisplayName("R5 — handler(컨트롤러 등)는 internal/service 를 직접 참조하지 않는다 — 포트로만")
	void handler_는_service_를_직접_참조하지_않는다() throws IOException {
		for (String module : domainModules()) {
			Path handlerDir = SRC.resolve(module).resolve("internal/handler");
			String servicePkg = "import kang20.ytcreator." + module + ".internal.service.";
			for (Path file : javaFiles(handlerDir)) {
				assertThat(read(file))
					.as("%s — handler 는 구현/support 를 직접 부르지 않는다. %s 는 포트(모듈 루트)만 참조해야 한다",
						module, file.getFileName())
					.doesNotContain(servicePkg);
			}
		}
	}

	// ── R6 ─────────────────────────────────────────────────────────────
	@Test
	@DisplayName("R6 — 구체 *Service 는 자기 패키지 밖에서 참조되지 않는다 — 밖은 포트로만")
	void 구체_service_는_밖에서_참조되지_않는다() throws IOException {
		List<Path> mainFiles = allMainJava();
		for (String module : domainModules()) {
			String servicePkg = "kang20.ytcreator." + module + ".internal.service";
			for (String serviceName : serviceSimpleNames(module)) {
				String importLine = "import " + servicePkg + "." + serviceName + ";";
				for (Path file : mainFiles) {
					if (read(file).contains(importLine)) {
						assertThat(packageName(file))
							.as("%s — 구체 %s 는 밖에서 참조하면 안 된다(포트로 부른다). %s 가 import 한다",
								module, serviceName, file.getFileName())
							.isEqualTo(servicePkg);
					}
				}
			}
		}
	}

	// ── R7 ─────────────────────────────────────────────────────────────
	/**
	 * {@code @Support} 는 <b>메타 {@code @Component}</b> 라 그것만으로 빈이 된다(2026-08-14 채택).
	 * support 는 정의상 Service 가 주입받는 협력자이므로 빈이 아닌 support 는 존재할 수 없고,
	 * 레이어를 안 가리는 유틸은 규약 §6 대로 애초에 support 가 아니다.
	 *
	 * <p>습관적으로 {@code @Component} 를 다시 붙이는 것을 여기서 막는다 — Javadoc 경고만으로는 아무도
	 * 막지 못한다. 설정 바인딩 부품의 {@code @ConfigurationProperties} 는 스캔이 아니라 바인딩 표식이라
	 * 대상이 아니다.
	 */
	@Test
	@DisplayName("R7 — @Support 에 @Component 를 함께 붙이지 않는다 — 메타 @Component 라 중복이다")
	void support_는_component_를_중복해서_붙이지_않는다() throws IOException {
		for (Path file : allMainJava()) {
			String source = read(file);
			if (!SUPPORT_USAGE.matcher(source).find()) {
				continue;
			}
			assertThat(COMPONENT_USAGE.matcher(source).find())
				.as("@Support 가 이미 메타 @Component 다 — %s 의 @Component 를 지워라", file.getFileName())
				.isFalse();
		}
	}

	// ── helpers ────────────────────────────────────────────────────────

	/** {@code internal/service} 디렉터리를 가진 최상위 모듈 = 도메인 모듈. */
	private static List<String> domainModules() throws IOException {
		try (Stream<Path> s = Files.list(SRC)) {
			return s.filter(Files::isDirectory)
				.filter(p -> Files.isDirectory(p.resolve("internal").resolve("service")))
				.map(p -> p.getFileName().toString())
				.sorted()
				.toList();
		}
	}

	/** {@code internal/service} 직속 파일들(package-info 제외) — R2 가 전부 {@code *Service} 임을 강제한다. */
	private static List<Path> serviceFiles(String module) throws IOException {
		try (Stream<Path> s = Files.list(SRC.resolve(module).resolve("internal/service"))) {
			return s.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.getFileName().toString().equals("package-info.java"))
				.toList();
		}
	}

	/**
	 * 모듈의 {@code *Service} 이름들. <b>이름 규칙이 아니라 실제 파일 목록에서 뽑는다</b> —
	 * {@code {Module}Service} 로 계산하면 Service 가 여럿일 때 나머지가 전부 "허용되지 않은 참조자"가 된다.
	 */
	private static List<String> serviceSimpleNames(String module) throws IOException {
		return serviceFiles(module).stream()
			.map(ArchitectureConventionTest::typeName)
			.filter(name -> name != null)
			.toList();
	}

	/** 모듈 루트 디렉터리 직속의 public 타입 이름들(하위 패키지 제외). */
	private static List<String> rootTypeNames(String module) throws IOException {
		try (Stream<Path> s = Files.list(SRC.resolve(module))) {
			return s.filter(Files::isRegularFile)
				.map(ArchitectureConventionTest::typeName)
				.filter(name -> name != null)
				.toList();
		}
	}

	private static List<Path> javaFiles(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.toList();
		}
	}

	private static List<Path> allMainJava() throws IOException {
		try (Stream<Path> s = Files.walk(SRC)) {
			return s.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.toList();
		}
	}

	/** 파일명에서 타입 이름을 뽑는다({@code Foo.java} → {@code Foo}). package-info 는 null. */
	private static String typeName(Path javaFile) {
		String name = javaFile.getFileName().toString();
		if (name.equals("package-info.java") || !name.endsWith(".java")) {
			return null;
		}
		return name.substring(0, name.length() - ".java".length());
	}

	private static String packageName(Path javaFile) {
		for (String line : read(javaFile).split("\n")) {
			String trimmed = line.strip();
			if (trimmed.startsWith("package ")) {
				return trimmed.substring("package ".length(), trimmed.indexOf(';')).strip();
			}
		}
		return "";
	}

	private static String read(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
