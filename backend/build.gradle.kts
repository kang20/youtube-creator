plugins {
	java
	jacoco
	// ⚠️ 버전은 setup 시점에 확인한다 — Boot 4.0.x ↔ Modulith 2.0.x / Boot 4.1.x ↔ Modulith 2.1.x
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("org.asciidoctor.jvm.convert") version "4.0.4"
}

group = "kang20"
version = "0.0.1-SNAPSHOT"
description = "ytcreator-backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// REST Docs 스니펫 생성 위치
val snippetsDir = file("build/generated-snippets")

// asciidoctor 전용 확장 클래스패스 — operation:: 매크로 + {snippets} 속성 제공
val asciidoctorExt: Configuration by configurations.creating

// Spring Modulith BOM — Boot 버전과 라인을 맞춘다 (Boot 4.0.x → 2.0.x)
val modulithVersion = "2.0.7"

dependencies {
	// ── Spring Modulith — 모듈 경계 강제 + 모듈 간 이벤트 ──────────────
	implementation(platform("org.springframework.modulith:spring-modulith-bom:$modulithVersion"))
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	// 이벤트 발행 레지스트리(트랜잭션 아웃박스) — 모듈 간 비동기 통신의 유실 방지
	implementation("org.springframework.modulith:spring-modulith-starter-jpa")
	// /actuator/modulith — 런타임에 모듈 구조 확인
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")

	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// 모니터링: Actuator + Micrometer Prometheus 레지스트리 (/actuator/prometheus)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

	// JWT — access 토큰 서명·검증 (auth-design.md §14-2, jjwt 0.12.x).
	// api 만 컴파일 표면이고 impl·jackson 은 런타임 전용 — 코드가 구현체에 붙는 것을 막는 jjwt 권장 구성.
	implementation("io.jsonwebtoken:jjwt-api:0.12.7")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.7")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.7")

	// 이미지 업로드가 필요하면: OCI Object Storage 의 S3 호환 API 에 presigned PUT
	// implementation(platform("software.amazon.awssdk:bom:2.46.7"))
	// implementation("software.amazon.awssdk:s3")

	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// Runtime
	runtimeOnly("com.mysql:mysql-connector-j")
	runtimeOnly("org.springframework.boot:spring-boot-docker-compose")

	// ── Test ─────────────────────────────────────────────────────────
	// 모듈 구조 검증(verify) + @ApplicationModuleTest + Scenario API
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	// 모듈 다이어그램/문서 자동 생성 (PlantUML · C4 · module canvas)
	testImplementation("org.springframework.modulith:spring-modulith-docs")

	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// Boot 4 모듈화로 @DataJpaTest 슬라이스가 별도 아티팩트로 분리됨
	testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
	// REST Docs: Boot 4 모듈화로 @AutoConfigureRestDocs 는 spring-boot-restdocs 에 분리됨
	testImplementation("org.springframework.boot:spring-boot-restdocs")
	testImplementation("org.springframework.restdocs:spring-restdocs-mockmvc")
	asciidoctorExt("org.springframework.restdocs:spring-restdocs-asciidoctor")
	testImplementation("p6spy:p6spy:3.9.1")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
	testLogging {
		showStandardStreams = true
	}
	outputs.dir(snippetsDir)
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}

// ── 커버리지 게이트 — 목표 100%, 실패선 LINE 95% / BRANCH 90% ─────────
// 제외는 "테스트로 의미를 검증할 게 없는 것"만. 서비스·컨트롤러·도메인은 절대 제외하지 않는다.
// 새 제외를 추가할 때는 docs/rule/testing.md 에 사유를 남긴다.
val coverageExclusions = listOf(
	"**/*Application.class",          // 부트스트랩 메인
	"**/config/**",                   // 스프링 설정 클래스(빈 정의만 — 통합테스트가 간접 검증)
	"**/*Config.class",
	"**/dto/**",                      // record DTO (동작 없음)
	"**/*Request.class",
	"**/*Response.class",
)

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				value = "COVEREDRATIO"
				minimum = "0.95".toBigDecimal()
			}
			limit {
				counter = "BRANCH"
				value = "COVEREDRATIO"
				minimum = "0.90".toBigDecimal()
			}
		}
	}
	classDirectories.setFrom(
		files(classDirectories.files.map {
			fileTree(it) { exclude(coverageExclusions) }
		})
	)
}

tasks.jacocoTestReport {
	classDirectories.setFrom(
		files(classDirectories.files.map {
			fileTree(it) { exclude(coverageExclusions) }
		})
	)
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}

// AsciidoctorJ 런타임을 spring-restdocs-asciidoctor 가 요구하는 버전에 맞춘다.
asciidoctorj {
	setVersion("3.0.0")
}

// REST Docs: 컨트롤러 테스트 스니펫 → HTML.
// adoc 소스는 src/docs/asciidoc/, HTML 출력만 레포 루트 docs/api/ 로 보낸다.
tasks.asciidoctor {
	configurations("asciidoctorExt")
	attributes(mapOf("snippets" to snippetsDir))
	inputs.dir(snippetsDir)
	dependsOn(tasks.test)
	setOutputDir(file("../docs/api"))
	baseDirFollowsSourceDir()
	sources(delegateClosureOf<org.gradle.api.tasks.util.PatternSet> {
		include("index.adoc")
	})
}
