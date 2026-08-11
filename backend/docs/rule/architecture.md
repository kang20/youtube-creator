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
├── shared/                            공유 커널 (OPEN 모듈: 예외·시간·보안·타입 ID 공통 부모)
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

## 모듈 내부 레이아웃 — `internal/` 은 레이어 서브패키지 (2026-08-11 채택)

`internal/` 이 커지면(payment 는 18파일) 평면 유지가 안 된다. **모듈 안쪽은 레이어로 자른다**:

```
{module}/internal/
├── entity/       엔티티 + 상태 enum
├── repository/   Spring Data 리포지토리
├── writer/       트랜잭션 쓰기 빈 (REQUIRES_NEW 경계 등 — 프록시 때문에 별도 빈인 것들)
├── client/       외부 시스템 접점 (HTTP 클라이언트·응답 매핑)
└── support/      정책·인증·마스킹·설정 바인딩
```

- ⚠️ **"왜 Modulith 인가"의 레이어 비판과 모순이 아니다** — 그 비판은 **프로젝트 최상위**를
  레이어로 잘라 모듈(기능) 경계가 사라지는 구조를 향한 것이다. 여기서는 **모듈 경계가 최상위**이고
  레이어는 모듈 **안쪽** 정리 방식이다. `internal/` 하위는 몇 단계든 전부 모듈 내부라
  `verify()`·`allowedDependencies` 에 영향이 없다.
- 필요한 레이어만 만든다 — 파일 2~3개짜리 모듈(auth·bootstrap)은 평면 유지가 맞다.
- 서브패키지 간 참조는 자유다(전부 내부). 단 **다른 모듈에서 보이는 것은 여전히 모듈 루트뿐**이다.

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

## 모듈 간 데이터 참조 — 타입화된 기본키 (2026-08-11 채택)

다른 모듈의 엔티티를 **데이터로** 가리켜야 할 때(FK 컬럼), 아래 셋을 전부 금지하고
**소유 모듈이 노출한 타입 ID 만** 저장·전달한다.

| 금지 | 왜 |
|---|---|
| 엔티티 참조 (`@ManyToOne User`) | `internal/` 침범. 모듈 경계가 무너진다 |
| 원시 `Long`/`String` PK | "어느 도메인의 Long 인가"가 시그니처에서 사라진다 — 컴파일러가 혼용을 못 잡는다 |
| 자연키 복제 (해시 등을 자기 테이블에 재저장) | 값이 두 곳에 존재해 동기화 무보장. 원천 값 변경(이관 등) 시 전 행 마이그레이션 |

**규칙**

- 모듈이 밖에 노출하는 것은 **도메인 엔티티가 아니라 기본키 타입뿐**이다.
  구체 타입 ID(`UserId` 등)는 **소유 모듈 루트**에 둔다("모듈 루트 public 타입만 외부에 보인다" 규칙 그대로).
  엔티티·리포지토리는 `internal/` 에 남는다.
- 공통 부모는 `shared/domain` 에 둔다: `ValueObject`(equals/hashCode) ·
  `LongTypeIdentifier`(Serializable + 언랩 접근자) · 대응 `*JavaType`(Hibernate 6+ 어댑터).
  **필요한 계열만 이식한다** — `StringTypeIdentifier` 계열은 String PK 도메인이 생길 때 추가한다
  (소비자 없는 부품은 과한 추상화다).
- **노출되는 식별자에만 타입을 입힌다.** 밖으로 나가지 않는 내부 대리키는 원시 `Long` 을 유지한다 —
  전 PK 일괄 적용은 과한 추상화다.
- 참조하는 쪽은 `allowedDependencies` 에 소유 모듈을 명시한다. 타입 ID 참조는
  "이벤트 우선" 원칙의 예외가 아니다 — **행위(호출)가 아니라 데이터(식별자)** 이기 때문이다.

**JPA 매핑 (선례: `C:\Spring_Study\youngZZ` — 검증 환경 Boot 4 / Hibernate 7)**

```java
// FK 컬럼 — 연관관계 없이 값 컬럼으로
@JavaType(UserIdJavaType.class)
@Column(name = "user_id", nullable = false, updatable = false)
private UserId userId;

// 노출되는 자기 PK 도 타입화 가능 — IDENTITY 채번값을 JavaType.wrap 이 감싼다
@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
@JavaType(UsageTicketIdJavaType.class)
private UsageTicketId id;
```

| 함정 | 처방 |
|---|---|
| `wrap`/`fromString` 이 **리플렉션으로 `(Long)` 생성자**를 부른다 — 컴파일 타임에 안 잡힌다 | 구체 ID 는 **박싱 타입 1개짜리 public 생성자** 필수. 예외 분기까지 테스트로 커버 |
| 하이드레이션이 그 생성자를 그대로 탄다 | **생성자에 검증 로직 금지.** 외부 입력 검증은 static 팩토리로 분리 |
| 네이티브 쿼리는 JavaType 을 안 탄다 | 네이티브 한정 `longValue()` 수동 언랩. **JPQL/derived query 는 타입 ID 그대로** |
| `ValueObject.equals` 가 strict `getClass()` 비교 | 구체 ID 는 **`final`** 선언 |
| `@ManyToOne` 이 없으므로 DB FK 가 자동 생성되지 않는다 | **물리 FK 를 걸지 않는 것이 기본**(모듈 자율성·삭제 순서 자유). 무결성은 UNIQUE 제약이 담당하고, 참조 관계는 수동 DDL 주석으로 표기 |
| JPQL 집계의 그룹 키가 타입 ID 로 돌아온다 | 리포지토리 javadoc 에 결과 타입 명시 |

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
| 다른 모듈 데이터를 키로 저장하고 싶다 | FK 가 필요한 것 | **소유 모듈이 노출한 타입 ID** 를 값 컬럼으로 (→ "타입화된 기본키" 절) |
| 공용 유틸을 모든 모듈이 참조 | `shared` 가 OPEN 이 아님 | `@ApplicationModule(type = Type.OPEN)` |
