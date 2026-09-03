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

class ArchitectureConventionTest {

	private static final Path SRC = Path.of("src/main/java/kang20/ytcreator");

	private static final Pattern SUPPORT_USAGE = Pattern.compile("(?m)^\\s*@Support\\b");

	private static final Pattern COMPONENT_USAGE = Pattern.compile("(?m)^\\s*@Component\\b");

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

	private static List<String> domainModules() throws IOException {
		try (Stream<Path> s = Files.list(SRC)) {
			return s.filter(Files::isDirectory)
				.filter(p -> Files.isDirectory(p.resolve("internal").resolve("service")))
				.map(p -> p.getFileName().toString())
				.sorted()
				.toList();
		}
	}

	private static List<Path> serviceFiles(String module) throws IOException {
		try (Stream<Path> s = Files.list(SRC.resolve(module).resolve("internal/service"))) {
			return s.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> !p.getFileName().toString().equals("package-info.java"))
				.toList();
		}
	}

	private static List<String> serviceSimpleNames(String module) throws IOException {
		return serviceFiles(module).stream()
			.map(ArchitectureConventionTest::typeName)
			.filter(name -> name != null)
			.toList();
	}

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
