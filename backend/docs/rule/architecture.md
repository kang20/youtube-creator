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
    ├── {Module}{책임}Port.java        모듈 공개 계약 — 책임별 포트 인터페이스 (다른 모듈이 부를 수 있는 유일한 축)
    ├── {Module}Event.java             다른 모듈에 알릴 사실 (record)
    ├── dto/                           요청·응답 record
    └── internal/                      구현 전부 — 모듈 밖 접근 금지 (→ "모듈 내부 레이아웃")
```

**핵심 규칙은 하나뿐이다: 모듈 루트 패키지의 public 타입만 외부에 보인다.**
`internal/` 이하는 public 이어도 다른 모듈이 참조하면 검증 테스트가 깨진다.

## 모듈 내부 레이아웃 — `internal/` 은 레이어 서브패키지 (2026-08-11 채택)

`internal/` 이 커지면(payment 는 18파일) 평면 유지가 안 된다. **모듈 안쪽은 레이어로 자른다**:

```
{module}/internal/
├── {Util}.java              (드묾) 레이어 안 가리는 모듈 내부 공용 유틸 — 예: OrderIdMask(로그 마스킹).
│                            service·handler 양쪽이 쓰면 support 가 아니다(support 는 Service 전용이므로).
├── entity/                  엔티티 + 상태 enum
├── handler/                 입출력 어댑터 — 방향으로 자른다
│   ├── inbound/             밖에서 들어오는 요청을 받는 쪽 — 컨트롤러 등
│   └── outbound/            우리가 밖을 부르는 쪽
│       ├── repository/      Spring Data 리포지토리 (DB 호출)
│       └── client/          외부 시스템 접점 (HTTP 클라이언트·응답 매핑)
└── service/                 오케스트레이션
    ├── {Module}Service.java 포트 구현 — 레이어를 엮는 유일한 본체
    └── support/             @Support 부품 — 정책·인증·마스킹·설정 바인딩·트랜잭션 쓰기 빈. Service 만 참조
```

- ⚠️ **"왜 Modulith 인가"의 레이어 비판과 모순이 아니다** — 그 비판은 **프로젝트 최상위**를
  레이어로 잘라 모듈(기능) 경계가 사라지는 구조를 향한 것이다. 여기서는 **모듈 경계가 최상위**이고
  레이어는 모듈 **안쪽** 정리 방식이다. `internal/` 하위는 몇 단계든 전부 모듈 내부라
  `verify()`·`allowedDependencies` 에 영향이 없다.
- 서브패키지 간 참조는 규약(아래)이 정한 방향만 허용한다. 단 **다른 모듈에서 보이는 것은 여전히 모듈 루트뿐**이다.

### Port·Service·Support 규약 (2026-08-12 채택 — 모든 도메인 모듈 강제)

Modulith 는 "다른 모듈이 `internal` 을 참조하는가"만 본다. 그 **아래의 모듈 내부 레이아웃**은
아래 규약으로 세우고, **`ArchitectureConventionTest` 가 소스 스캔으로 강제**한다(source of truth).
대상은 **도메인 모듈**(= `internal/service` 를 가진 모듈: auth·payment). `shared`·`config`(OPEN 공용)와
`bootstrap`(저장소 없는 집계 어댑터)은 예외다.

1. **공개 계약은 `*Port` 인터페이스뿐이다.** 모듈 루트에는 **책임별 포트**(`{Module}{책임}Port` —
   `PaymentReaderPort`·`PaymentConsumePort`·`PaymentPurchasePort`·`PaymentWebhookPort`, `AuthPort`)와
   타입 ID 만 둔다. 네이밍만으로 "공개 인터페이스이며 어떤 책임인지"가 드러난다.
   포트는 **소비자·책임 단위**로 자른다 — 한 포트에 모든 메서드를 몰면 "누가 무엇을 쓰는가"가 흐려진다.
2. **`{Module}Service` 는 `internal/service` 직속의 유일한 클래스**이고, 그 포트들을 `implements` 한다.
   HTTP 전용 흐름(컨트롤러만 부르는 메서드)도 포트에 얹어 노출하되, 그 포트의 실질 소비자는
   이 모듈의 컨트롤러다(inbound driving port).
3. **구체 `*Service` 는 아무도 직접 참조하지 않는다** — 밖(다른 모듈)도, 안(컨트롤러)도 **포트로만** 부른다.
   Boot 기본이 CGLIB(클래스 프록시)라 포트가 있어도 `@Transactional` 프록시 빈이 정상 주입된다.
4. **`internal/service` 밑에서 Service 를 뺀 나머지는 전부 `support/` 로 내리고 `@Support` 를 단다.**
   `@Support`({@code shared/support}) 의 계약은 하나다: **같은 모듈의 `*Service` 만 support 를 참조한다.**
   컨트롤러·리포지토리·엔티티·다른 support 는 support 를 못 부른다 — 오케스트레이션의 단일 주인은 Service 다.
5. **컨트롤러는 `internal/handler/inbound/` 에 두고, `internal/service` 를 직접 참조하지 않는다** —
   포트(모듈 루트)로만 부른다. 컴포넌트 스캔은 패키지와 무관해 매핑·REST Docs 산출물에 영향이 없다.
6. **레이어를 안 가리는 모듈 내부 공용 유틸**(예: `OrderIdMask` — service·client 양쪽이 쓰는 로그 마스킹)은
   support 가 아니다(support 는 Service 전용). `internal/` 루트의 평범한 클래스로 둔다.

- ⚠️ 포트 분리는 **외부 소비자의 표면이 구현의 public 표면보다 실제로 작을 때만** 정당하다.
  소비자가 없거나 표면이 같다면 굳이 포트를 나누지 말고 최소 하나만 둔다(과한 추상화 금지).
- `bootstrap` 은 저장소·서비스가 없는 **집계 어댑터**라 이 규약에서 빠진다 — 컨트롤러가 다른 모듈의
  포트(`AuthPort`·`PaymentReaderPort`)를 조립할 뿐이다.

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

## 구조 검증 테스트 (필수 — 두 층)

**① 모듈 경계 — `ModularityTest`** (Modulith `verify()`)

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

**② 모듈 내부 레이아웃 — `ArchitectureConventionTest`** (소스 스캔)

`verify()` 는 **모듈 경계까지**만 본다. 그 아래 "Port·Service·Support 규약"(위)은 이 테스트가 강제한다.
도메인 모듈(= `internal/service` 보유)에 대해 소스 import 를 스캔해 R1~R6 을 단언한다:

| 규칙 | 잡는 것 |
|---|---|
| R1 | 모듈 루트에 공개 `*Port` 가 있다 |
| R2 | `internal/service` 직속 = `*Service` 하나, 그것은 `*Port` 를 구현한다 |
| R3 | `service/support` 타입은 전부 `@Support`, `@Support` 는 거기에만 |
| R4 | `@Support` 는 같은 모듈 `*Service` 만 참조한다 |
| R5 | `handler`(컨트롤러 등)는 `internal/service` 를 직접 참조하지 않는다 — 포트로만 |
| R6 | 구체 `*Service` 는 자기 패키지 밖에서 참조되지 않는다 |

- 두 테스트 모두 **CI 필수**다. 배포 워크플로의 `./gradlew test` 에 포함된다.
- 런타임 확인: `/actuator/modulith` (외부에는 Caddy 가 403 으로 막는다).

## 새 모듈 추가 시 만드는 파일

```
{name}/package-info.java
{name}/{Name}{책임}Port.java                       (공개 계약 — 책임별 포트. R1)
{name}/dto/
{name}/internal/service/{Name}Service.java        (포트 구현 — service 직속 유일. R2)
{name}/internal/service/support/*.java            (@Support 부품 — Service 만 참조. R3·R4)
{name}/internal/handler/inbound/{Name}Controller.java   (HTTP 어댑터 — 포트로만 부른다. R5)
{name}/internal/handler/outbound/repository/{Name}Repository.java
{name}/internal/entity/{Name}.java
src/test/.../{name}/{Name}ControllerTest.java     (REST Docs — 포트를 @MockitoBean)
src/test/.../{name}/{Name}ModuleTest.java         (@ApplicationModuleTest)
src/docs/asciidoc/{name}.adoc
```

`ArchitectureConventionTest`(R1~R6)가 이 골격을 강제한다 — 새 모듈도 자동으로 규약에 걸린다.

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
