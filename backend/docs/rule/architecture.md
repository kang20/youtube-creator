# 아키텍처 규칙 — Spring Modulith

> 단일 진실원천. CLAUDE.md·스킬·도메인 문서가 이 문서를 링크한다.

## 왜 Modulith 인가

레이어(controller/service/repository)로 자르면 **기능이 세 군데로 흩어지고** 경계는 사람이 지켜야 한다.
Modulith 는 **패키지 = 애플리케이션 모듈**로 보고, 모듈 경계를 **테스트로 강제**한다.
경계가 코드로 검증되므로 별도 ArchUnit 규칙을 손으로 쓸 필요가 없다.

## 패키지 레이아웃

```
kang20.ytcreator/
├── ytcreatorApplication.java        @SpringBootApplication — 모듈 스캔의 루트
├── shared/                            공유 커널 (OPEN 모듈: 예외·시간·ID VO·보안)
│   └── package-info.java              @ApplicationModule(type = Type.OPEN)
├── config/                            전역 스프링 설정 (OPEN 모듈)
│   └── package-info.java
└── {module}/                          ← 애플리케이션 모듈 하나 = 도메인 하나
    ├── package-info.java              @ApplicationModule(displayName, allowedDependencies)
    ├── {Module}Controller.java        HTTP 진입점 — 모듈 안에 둔다
    ├── {Module}Service.java           모듈 공개 API (다른 모듈이 부를 수 있는 유일한 축)
    ├── {Module}Event.java             다른 모듈에 알릴 사실 (record)
    ├── dto/                           요청·응답 record
    └── internal/                      엔티티·리포지토리·구현체 — 모듈 밖 접근 금지
```

**핵심 규칙은 하나뿐이다: 모듈 루트 패키지의 public 타입만 외부에 보인다.**
`internal/` 이하는 public 이어도 다른 모듈이 참조하면 검증 테스트가 깨진다.

## 모듈 간 통신 — 이벤트가 기본

```java
// 발행 (post 모듈)
events.publishEvent(new PostCreated(postId, memeId));

// 구독 (notification 모듈) — 트랜잭션 커밋 후 비동기 실행 + 아웃박스에 기록되어 유실 방지
@ApplicationModuleListener
void on(PostCreated event) { ... }
```

- 직접 호출은 **동기 응답이 반드시 필요할 때만**. 그 경우 반드시 `allowedDependencies` 에 명시한다.
- `@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener`.
  이벤트 발행 레지스트리(`spring-modulith-starter-jpa`)가 미완료 이벤트를 DB 에 남겨 재시도한다.

## package-info.java 작성 예

```java
@ApplicationModule(
    displayName = "게시글",
    allowedDependencies = { "shared", "meme :: api" }   // 명시 안 하면 아무 모듈도 못 부른다
)
package kang20.ytcreator.post;

import org.springframework.modulith.ApplicationModule;
```

`allowedDependencies` 를 **빈 배열로 두면 완전 격리**된다. 새 의존을 추가할 땐 여기에 적으면서
"이 결합이 정말 필요한가"를 한 번 되묻는 게 이 구조의 요점이다.

## 구조 검증 테스트 (필수 — 모든 프로젝트에 1개)

```java
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(ytcreatorApplication.class);

    @Test
    void 모듈_경계를_지킨다() {
        MODULES.verify();          // 순환 의존·internal 침범·미허용 의존을 전부 잡는다
    }

    @Test
    void 모듈_문서를_생성한다() throws Exception {
        new Documenter(MODULES)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
            .writeModuleCanvases();   // build/spring-modulith-docs/
    }
}
```

- `verify()` 가 잡는 것: 순환 의존, `internal` 접근, `allowedDependencies` 위반, 모듈 미선언 패키지.
- 이 테스트는 **CI 필수**다. 배포 워크플로의 `./gradlew test` 에 이미 포함된다.
- 런타임 확인: `/actuator/modulith` (외부에는 Caddy 가 403 으로 막는다).

## 새 모듈 추가 시 만드는 파일

```
{name}/package-info.java
{name}/{Name}Controller.java
{name}/{Name}Service.java
{name}/dto/
{name}/internal/{Name}.java            (엔티티)
{name}/internal/{Name}Repository.java
src/test/.../{name}/{Name}ControllerTest.java     (REST Docs)
src/test/.../{name}/{Name}ModuleTest.java         (@ApplicationModuleTest)
src/docs/asciidoc/{name}.adoc
```

설계 문서(`docs/domain/{name}.md` + `{name}-design.md`)를 `/b-usecase` → `/b-develop-design` 으로
**먼저** 만들고, 골격은 설계서 §4 모듈 매핑대로 구현 첫 커밋에서 직접 만든다.

## 흔한 실수

| 증상 | 원인 | 해결 |
|---|---|---|
| `verify()` 가 "module not declared" | 최상위 패키지에 `package-info.java` 없음 | 모듈로 만들거나 `shared` 로 옮긴다 |
| 두 모듈이 서로 참조 | 순환 의존 | 한쪽을 이벤트 구독으로 뒤집는다 |
| 엔티티를 다른 모듈에서 쓰고 싶다 | 경계 설계 실패 신호 | 필요한 값만 담은 record 를 모듈 루트에 노출하거나 이벤트로 전달 |
| 공용 유틸을 모든 모듈이 참조 | `shared` 가 OPEN 이 아님 | `@ApplicationModule(type = Type.OPEN)` |
