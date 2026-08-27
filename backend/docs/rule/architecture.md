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
├── shared/                            공유 커널 (OPEN 모듈)
│   ├── package-info.java              @ApplicationModule(type = Type.OPEN)
│   ├── domain/                        ValueObject · LongTypeIdentifier · BaseTimeEntity
│   ├── exception/                     ErrorCode · BusinessException · GlobalExceptionHandler
│   └── support/                       @Support 표식 · UniqueRace(동시성 골격) — 상태 없는 공용 부품
├── config/                            전역 스프링 설정 (OPEN 모듈)
│   └── package-info.java
└── {module}/                          ← 애플리케이션 모듈 하나 = 도메인 하나
    ├── package-info.java              @ApplicationModule(displayName, allowedDependencies)
    ├── {Module}{책임}Port.java        ⚠️ 다른 모듈이 실제로 부르는 포트만 여기 온다 (→ "공개 표면")
    │                                  자기 모듈 핸들러만 부르면 internal/port/ 다
    ├── {Module}Event.java             다른 모듈에 알릴 사실 (record)
    ├── {Value}.java                   경계를 넘는 값 객체·타입 ID — UserId·OrderId (→ "타입화된 식별자")
    ├── dto/                           경계를 넘는 요청·응답 record
    │   └── package-info.java          @NamedInterface("dto") ← 이 선언이 공개의 근거다
    └── internal/                      구현 전부 — 모듈 밖 접근 금지 (→ "모듈 내부 레이아웃")
```

**핵심 규칙은 하나뿐이다: 모듈 루트 패키지의 public 타입만 외부에 보인다.**
`internal/` 이하는 public 이어도 다른 모듈이 참조하면 검증 테스트가 깨진다.

### 공개 표면 — 루트에 있으려면 밖에서 쓰여야 한다 (2026-08-15 채택)

Modulith 는 "루트에 둔 것을 밖에서 볼 수 있다"만 보장한다. **"둘 만한가"는 보지 않는다.**
그래서 소비자 없이 루트에 올라온 타입이 쌓였고, 실제로 5개 포트 중 밖에서 불리는 것은
`AuthPort` 하나뿐이었다(2026-08-15 전수 조사). 나머지 넷은 자기 모듈의 컨트롤러·리스너만 부르는
**inbound driving port** 였다.

**규칙을 뒤집는다 — `R1`: 모듈 루트의 공개 타입은 다른 모듈이 실제로 참조한다.**
안 쓰이면 `internal` 로 내리고, 소비자가 생기는 시점에 올린다.

| | 자리 | 판별 |
|---|---|---|
| 밖에서 부르는 포트 | `{module}/{X}Port.java` | 다른 모듈의 `src/main` 이 import 한다 |
| 자기 모듈만 부르는 포트 | `{module}/internal/port/{X}Port.java` | 컨트롤러·리스너만 부른다 |

- **포트를 없애는 게 아니다.** 핸들러가 구현을 직접 잡지 않게 하는 역전(R5)은 그대로다 —
  공개 여부만 분리한다.
- **`internal/service/` 옆에 두지 않는다.** 거기 두면 핸들러가 `internal.service.*` 를 import 하게 돼
  R5 가 무너진다. 그래서 별도 패키지 `internal/port/` 다.
- **기준은 `src/main` 참조뿐이다.** 흐름 테스트가 다른 모듈의 포트를 구동하는 것은 컨트롤러·리스너
  대역이지 프로덕션 계약이 아니다 — 테스트를 세면 "안 쓰는데 공개"가 그대로 남는다.
- 포트 **존재** 자체는 R2(`*Service` 가 `*Port` 를 구현한다)가 이미 강제한다. R1 은 **자리**만 본다.
- 같은 잣대로 `auth/UserPrincipal` 도 `auth/internal/` 로 내려갔다 — 검증 부품과 게이트 필터
  사이에서만 오가는 값이라 공개할 이유가 없었다.
- 🔶 **`dto/` 의 `@NamedInterface` 는 아직 이 잣대를 적용하지 않았다.** `payment :: dto`·
  `subscription :: dto` 는 현재 외부 소비자가 없다 — 읽기 모델(Entitlement)이 생기면 소비자가
  붙으므로 그때 함께 판정한다.

- ⚠️ **`dto/` 가 서브패키지라서 자동으로 보이는 게 아니다.** Modulith 는 모듈 루트만 API 로 보므로
  `dto/package-info.java` 에 `@NamedInterface("dto")` 를 선언해야 노출된다. 참조하는 쪽은
  `allowedDependencies` 에 **`"auth :: dto"` 처럼 명명 인터페이스까지** 적는다(bootstrap 선례) —
  모듈만 적으면 루트 타입만 열린다.
- **모듈 밖으로 안 나가는 record 를 `dto/` 에 두지 않는다.** 그건 공개 표면을 이유 없이 넓히는 것이다
  (→ "모듈 내부 레이아웃"의 `internal/entity/dto/`).

## 모듈 내부 레이아웃 — `internal/` 은 레이어 서브패키지 (2026-08-11 채택)

`internal/` 이 열 파일을 넘어가면 평면 유지가 안 된다. **모듈 안쪽은 레이어로 자른다**:

```
{module}/internal/
├── {Util}.java              (드묾) 레이어 안 가리는 모듈 내부 공용 유틸.
│                            service·handler 양쪽이 쓰면 support 가 아니다(support 는 Service 전용이므로).
│                            ⚠️ 지금 이 자리에 사는 클래스는 없다 — 규약 §6 참고
├── entity/                  엔티티 + 상태 enum
│   ├── {Entity}.java
│   ├── {Value}Converter.java   값 객체의 JPA 매핑 어댑터 (→ "타입화된 식별자")
│   └── dto/                 모듈 내부 전용 record — 엔티티를 만드는 입력·내부 레이어 간 값 묶음
│                            예: GrantRequest(검증 산출물 → Order.grant 의 입력).
│                            ⚠️ 모듈 루트의 dto/ 와 다르다 — 이건 밖으로 안 나간다
├── port/                    비공개 포트 — 자기 모듈 핸들러만 부르는 driving port (→ "공개 표면")
│                            handler 가 구현을 직접 잡지 않게 하는 역전은 유지하되 공개는 하지 않는다.
│                            ⚠️ service/ 옆이 아니라 별도 패키지다 — 거기 두면 R5 가 무너진다
├── handler/                 입출력 어댑터 — 방향으로 자른다
│   ├── inbound/             밖에서 들어오는 요청을 받는 쪽 — 컨트롤러 등
│   └── outbound/            우리가 밖을 부르는 쪽
│       ├── repository/      Spring Data 리포지토리 (DB 호출)
│       └── client/          외부 시스템 접점 (HTTP 클라이언트·응답 매핑)
└── service/                 오케스트레이션
    ├── {Module}Service.java 포트 구현 — 레이어를 엮는 유일한 본체
    └── support/             @Support 부품 — 정책·외부 검증·설정 바인딩·트랜잭션 쓰기 빈. Service 만 참조
                             (레이어를 안 가리는 유틸은 여기가 아니라 internal/ 루트 — 규약 §6)
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

1. **공개 계약은 모듈 루트에만 둔다 — 그리고 루트에는 공개 계약만 둔다**(2026-08-15 갱신).
   **밖에서 실제로 불리는 책임별 포트**(`{Module}{책임}Port` — 현재는 `AuthPort` 하나)와
   **경계를 넘는 값 객체·타입 ID**(`UserId`·`OrderId`),
   그리고 **(auth v4) 다른 모듈(config)이 조립하는 게이트 부품**
   (`JwtAuthenticationFilter`·`TokenAuthenticationEntryPoint`·`UserAuthentication`·
   `CurrentUser(ArgumentResolver)`) 이다 — 게이트 부품은 HTTP 어댑터가 아니라 공개 계약이다
   (auth-design §14-1). 네이밍만으로 "공개 인터페이스이며 어떤 책임인지"가 드러난다.
   포트는 **소비자·책임 단위**로 자른다 — 한 포트에 모든 메서드를 몰면 "누가 무엇을 쓰는가"가 흐려진다.
   - ⚠️ **밖에서 안 부르는 포트는 `internal/port/` 다**(→ "공개 표면"). 컨트롤러·리스너만 부르는
     inbound driving port 가 여기 해당한다 — 현재 `PaymentPurchasePort`·`CreditGrantPort`·
     `SubscriptionGrantPort`·`SubscriptionStatusPort` 넷 전부.
2. **`internal/service` 직속은 전부 `*Service` 이고, 각각 `*Port` 를 `implements` 한다.**
   HTTP 전용 흐름(컨트롤러만 부르는 메서드)도 포트에 얹어 노출하되, 그 포트의 실질 소비자는
   이 모듈의 컨트롤러이므로 **그 포트는 `internal/port/` 에 둔다**.
   - **개수는 제한하지 않는다**(2026-08-18 갱신). 구 규약은 Service 를 하나로 못 박았는데,
     책임이 갈리는 흐름을 나눌 자리가 아예 없었다 — 남는 선택지는 `support/` 뿐이고 support 는
     R4 때문에 컨트롤러가 못 부른다. 이제 **책임이 갈리면 Service 를 나눈다**.
   - ⚠️ 다만 **나누는 기준은 "포트 소비자가 다른가"** 다 — 메서드가 늘었다고 쪼개지 않는다(§ 아래
     "포트 분리는 …" 경고와 같은 잣대). 한 흐름을 두 Service 로 갈라 **호출 순서가 계약이 되면**
     그 순서를 빠뜨리는 것이 조용한 버그가 된다.
3. **구체 `*Service` 는 아무도 직접 참조하지 않는다** — 밖(다른 모듈)도, 안(컨트롤러)도 **포트로만** 부른다.
   Boot 기본이 CGLIB(클래스 프록시)라 포트가 있어도 `@Transactional` 프록시 빈이 정상 주입된다.
4. **`internal/service` 밑에서 Service 를 뺀 나머지는 전부 `support/` 로 내리고 `@Support` 를 단다.**
   `@Support`(`shared/support`) 의 계약: **같은 모듈의 `*Service` 와 모듈 루트의 게이트 부품
   (접미사 `Filter`·`Resolver` — auth v4)만 support 를 참조한다.** 컨트롤러·리포지토리·엔티티·다른
   support 는 support 를 못 부른다 — 오케스트레이션의 단일 주인은 Service 이고, 게이트 부품의 예외는
   검증 부품(JwtSupport)을 부르는 것이 그 존재 이유라서다(auth-design §14-1).
   **`@Support` 는 메타 `@Component` 다 — 이것만으로 빈이 된다**(2026-08-14). support 는 정의상
   Service 가 주입받는 협력자라 빈이 아닌 support 는 존재할 수 없고, 유틸은 §6 대로 애초에 support 가
   아니다. 그래서 **`@Component` 를 함께 붙이지 않는다(R7 이 강제)** — 설정 바인딩 부품만
   `@ConfigurationProperties` 를 추가로 단다(스캔이 아니라 바인딩 표식이라서).
5. **컨트롤러는 `internal/handler/inbound/` 에 두고, `internal/service` 를 직접 참조하지 않는다** —
   포트(모듈 루트)로만 부른다. 컴포넌트 스캔은 패키지와 무관해 매핑·REST Docs 산출물에 영향이 없다.
6. **레이어를 안 가리는 모듈 내부 공용 유틸**은 support 가 아니다(support 는 Service 전용).
   `internal/` 루트의 평범한 클래스로 둔다.
   - ⚠️ **그 전에 "값 객체가 할 일 아닌가"를 먼저 묻는다.** 로그 마스킹은 원래 `OrderIdMask` 유틸이었는데,
     **호출자가 부르지 않으면 새는 구조**였다. `OrderId.toString()` 이 마스킹을 반환하도록 바꾸자
     문자열 연결·로그 포맷·디버거·예외 메시지가 전부 자동으로 안전해졌고 유틸은 사라졌다(2026-08-14).
     **값의 표현 규칙은 유틸이 아니라 값 객체가 강제한다** — 그래서 지금 이 자리에 사는 클래스는 없다.

- ⚠️ 포트 분리는 **외부 소비자의 표면이 구현의 public 표면보다 실제로 작을 때만** 정당하다.
  소비자가 없거나 표면이 같다면 굳이 포트를 나누지 말고 최소 하나만 둔다(과한 추상화 금지).
  - **선례**: payment 는 `Reader`·`Consume`·`Purchase`·`Webhook` 4종을 미리 세웠다가, 실제 소비자가
    지급 하나뿐임이 드러나 `PaymentPurchasePort` 하나로 되돌렸다(2026-08-14). **포트는 설계 시점이 아니라
    소비자가 생기는 시점에 만든다.**
- `bootstrap` 은 저장소·서비스가 없는 **집계 어댑터**라 이 규약에서 빠진다 — 컨트롤러가 다른 모듈의
  포트(현재는 `AuthPort` 하나)를 조립할 뿐이다.

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

## 모듈 간 데이터 참조 — 타입화된 식별자 (2026-08-11 채택 · 2026-08-14 문자열 자연키 계열 추가)

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
  **필요한 계열만 이식한다** — 소비자 없는 부품은 과한 추상화다.
- **노출되는 식별자에만 타입을 입힌다.** 밖으로 나가지 않는 내부 대리키는 원시 `Long` 을 유지한다 —
  전 PK 일괄 적용은 과한 추상화다.

**세 계열이 있다 — 계보가 다르면 매핑 방식도 다르다**

| | 대리키 계열 (`UserId`) | 문자열 자연키 계열 (`OrderId`) | 수량 VO 계열 (`Balance`) |
|---|---|---|---|
| 정체 | 우리가 채번한 `Long` 기본키 | **외부(토스)가 발급한** `String` 자연키 | 식별자가 아닌 **값**(개수·금액) |
| 부모 | `LongTypeIdentifier` | `ValueObject` **직속** | **없음 — `record`** (동등성이 언어 기본) |
| JPA 매핑 | `@JavaType(UserIdJavaType.class)` | `@Convert(converter = OrderIdConverter.class)` | `@Embeddable` + `@AttributeOverride` |
| 어댑터 위치 | **모듈 루트(공개)** | **`internal/entity`(비공개)** | 어댑터 파일 없음 |

- 🔴 **식별자가 아니면 `record` + `@Embeddable` 이 기본값이다**(2026-08-15 전환, `Balance` 선례).
  Hibernate 6.2+ 는 record 임베더블을 **정규 생성자로 인스턴스화**하므로 불변식 검증이 읽기 경로에서도
  돈다 — 별도 어댑터 클래스가 필요 없어 `AttributeConverter` 보다 파일 하나가 준다.
- ⚠️ **그 record 를 일반 클래스로 바꾸지 마라.** no-arg 생성자 + 필드 주입으로 돌아가 검증이 **조용히**
  죽는다. 굳이 클래스로 써야 하면 생성자에 `@Instantiator` 가 필수다. 이 계약은 컴파일러가 못 잡으므로
  **음수 행을 읽으면 터지는 테스트**(`BalanceHydrationTest`)로 고정한다.
- 기존 `AttributeConverter`(`OrderId`)를 굳이 갈아엎지 않는다 — 외부 발급 자연키는 `@NaturalId` 와
  함께 쓰이고 이미 검증된 선례다. 새로 만드는 **비식별자 값 객체만** record + `@Embeddable` 로 간다.

- **`StringTypeIdentifier` 공통 부모를 만들지 않았다**(2026-08-14). 문자열 자연키는 언랩 규약도
  채번 규약도 공유하지 않아, 부모를 세워도 담을 공통 동작이 없다. 구현체 하나뿐인 추상은 만들지 않는다.
- 🔴 **영속화 어댑터의 노출 여부는 "다른 모듈이 그 값을 저장하는가"로 정한다.** payment 가 `user_id` 를
  자기 테이블에 저장하므로 `UserIdJavaType` 은 공개다. 반대로 **`order_id` 를 저장할 모듈은 없으므로**
  `OrderIdConverter` 는 `internal` 에 숨긴다 — 타입은 공개하되 어댑터는 숨기는 것이 기본값이다.
- **`autoApply = true` 를 켜지 않는다.** 필드마다 `@Convert` 로 명시한다 — 전역 적용은 나중에 다른
  문자열 VO 가 생겼을 때 조용히 잘못 걸린다.
- **경계를 넘는 값은 원시 문자열로 넘기지 않는다.** `grant(UserId, OrderId)` 처럼 타입으로 받으면
  마스킹 같은 **표현 보장이 값과 함께 이동한다**. 문자열로 받으면 그 보장이 경계에서 끊긴다(규약 §6).
- 참조하는 쪽은 `allowedDependencies` 에 소유 모듈을 명시한다. 타입 ID 참조는
  "이벤트 우선" 원칙의 예외가 아니다 — **행위(호출)가 아니라 데이터(식별자)** 이기 때문이다.

**JPA 매핑 (선례: `C:\Spring_Study\youngZZ` — 검증 환경 Boot 4 / Hibernate 7)**

```java
// ① FK 컬럼 — 연관관계 없이 값 컬럼으로 (대리키 계열)
@JavaType(UserIdJavaType.class)
@Column(name = "user_id", nullable = false, updatable = false)
private UserId userId;

// ② 외부 발급 문자열 자연키 — AttributeConverter + @NaturalId (Order 선례)
@Convert(converter = OrderIdConverter.class)
@NaturalId
@Column(name = "order_id", nullable = false, updatable = false, length = 64)
private OrderId orderId;

// ③ 비식별자 값 객체 — record @Embeddable (Balance 선례). 컬럼 1개여도 마찬가지다
@Embedded
@AttributeOverride(name = "value", column = @Column(name = "balance", nullable = false))
private Balance balance;

// ④ 노출되는 자기 PK 도 타입화 가능 — IDENTITY 채번값을 JavaType.wrap 이 감싼다
@Id @GeneratedValue(strategy = GenerationType.IDENTITY)
@JavaType(FooIdJavaType.class)
private FooId id;
```

- ②는 **대리키(`Long id`)와 함께 산다.** 자연키를 PK 로 올리지 않는다 — 외부가 발급한 값의 형식·길이가
  바뀌면 전 테이블의 FK 가 함께 흔들린다. 자연키는 `UNIQUE` 제약으로 지키고, PK 는 우리가 채번한다.
- 그 `UNIQUE` 제약은 무결성 장치이자 **동시성 심판**이다(→ 다음 절).

| 함정 | 처방 |
|---|---|
| `wrap`/`fromString` 이 **리플렉션으로 `(Long)` 생성자**를 부른다 — 컴파일 타임에 안 잡힌다 | 구체 ID 는 **박싱 타입 1개짜리 public 생성자** 필수. 예외 분기까지 테스트로 커버 |
| 하이드레이션이 그 생성자를 그대로 탄다 | **생성자에 검증 로직 금지**(`@JavaType` 계열 한정). 외부 입력 검증은 static 팩토리로 분리 |
| 반대로 `AttributeConverter` 계열은 하이드레이션이 **컨버터를 명시적으로 거친다** | 그래서 **생성자 검증이 허용된다** — `OrderId` 는 생성자에서 null·blank 를 막는다. 위 금지 규칙을 여기까지 확대 적용하지 마라 |
| record `@Embeddable` 도 **정규 생성자를 탄다**(Hibernate 6.2+ 실측) | 여기도 **생성자 검증이 허용된다**. "하이드레이션은 검증을 우회한다"는 `@JavaType` 계열 한정 규칙이다 — 임베더블까지 넓히지 마라 |
| record 를 일반 클래스로 리팩터하면 필드 주입으로 돌아가 검증이 **조용히** 죽는다 | `@Instantiator` 필수. 컴파일러가 못 잡으므로 **불량 행 읽기 테스트**로 계약을 고정한다 |
| 네이티브 쿼리는 JavaType 을 안 탄다 | 네이티브 한정 `longValue()` 수동 언랩. **JPQL/derived query 는 타입 ID 그대로** |
| `ValueObject.equals` 가 strict `getClass()` 비교 | 구체 ID 는 **`final`** 선언 |
| `@ManyToOne` 이 없으므로 DB FK 가 자동 생성되지 않는다 | **물리 FK 를 걸지 않는 것이 기본**(모듈 자율성·삭제 순서 자유). 무결성은 UNIQUE 제약이 담당하고, 참조 관계는 수동 DDL 주석으로 표기 |
| JPQL 집계의 그룹 키가 타입 ID 로 돌아온다 | 리포지토리 javadoc 에 결과 타입 명시 |

## 동시성 — UNIQUE 제약을 심판으로 쓴다 (2026-08-14 채택)

**선점 락도 분산 락도 두지 않는다.** 낙관적으로 삽입하고, `UNIQUE` 위반이 나면 그것 자체가
**"누군가 이미 넣었다"는 확정 사실**이므로 승자 행을 다시 읽어 결과를 맞춘다.
골격은 `shared/support/UniqueRace.firstWriterWins` 에 있다.

**이 방식을 고르는 이유**: 락은 "동시에 들어오지 못하게" 막지만, 우리가 실제로 원하는 건
**"동시에 들어와도 결과가 하나로 수렴하는 것"** 이다. 지급은 복원·재시도·타임아웃 후 성공이
정상적으로 중복을 만들고, 그때 **멱등한 재요청과 동시 요청이 같은 경로로 수렴**해야 한다.
락은 이 둘을 다른 경로로 갈라놓는다.

```java
// PaymentService.grant — 3층 구조
Optional<Order> order = orderRepository.findByOrderId(orderId);
if (order.isPresent()) {
    return replayOrReject(order.get(), userId);      // ① 선판정: 이미 있으면 외부를 부르지 않는다
}
GrantRequest request = orderVerifier.verify(orderId); // ② 외부 왕복 — 트랜잭션 밖이다

return UniqueRace.firstWriterWins(
    () -> { ledgerWriter.record(request, userId);     // ③ 삽입 시도 (REQUIRES_NEW + flush)
            return new GrantResult(true, request.productType()); },
    () -> orderRepository.findByOrderId(orderId)      // ④ 졌으면 승자 행으로 결과를 맞춘다
              .map(winner -> replayOrReject(winner, userId)),
    orderId);                                         // ⑤ 판정 불가 로그용 — 마스킹된 값이 찍힌다
```

**규칙**

1. 🔴 **불변식의 근거는 코드가 아니라 제약이다.** ①의 조회는 **빠른 경로일 뿐 방어가 아니다** —
   동시에 들어온 두 요청은 둘 다 빈 결과를 본다. `UNIQUE(order_id)` 만이 하나를 떨어뜨린다.
   **"조회해서 없으면 삽입"을 멱등의 근거로 쓰지 마라.**
2. 🔴 **삽입은 `@Transactional(REQUIRES_NEW)` 를 단 별도 빈(`*LedgerWriter` — `@Support`)에 맡기고
   flush 까지 끝낸다.** 호출자와 같은 트랜잭션에서 터지면 rollback-only 로 마킹돼 ④의 재조회가
   무의미해진다. **터진 트랜잭션 안에서 예외를 잡아 살리려 하지 마라 — 못 산다.**
3. 🔴 **포트 진입점은 `@Transactional` 을 붙이지 않고, 호출자도 트랜잭션 밖에서 부른다.**
   안에서 부르면 ⓐ 외부 왕복이 DB 커넥션을 물고 네트워크를 기다리고, ⓑ ④의 재조회가 호출자
   트랜잭션 스냅샷에 갇혀 **경쟁자 행을 보지 못한다.** 이 전제는 **포트 javadoc 에 명시한다** —
   컴파일러가 못 잡는 계약이라 문서가 유일한 방어다.
4. **UNIQUE 위반만 경쟁으로 읽는다.** `NOT NULL`·길이 초과도 `DataIntegrityViolationException` 이다 —
   구분 없이 삼키면 진짜 버그가 "경쟁에 졌다"로 위장된다. JPA 경로는 `DuplicateKeyException` 으로
   세분화되지 않으므로(그건 `JdbcTemplate` 한정) 원인 체인의 Hibernate `ConstraintKind` 로 좁힌다.
5. **대상 테이블의 UNIQUE 제약은 하나여야 한다.** 둘 이상이면 "아무 UNIQUE 위반이나 → 이 행 재조회"가
   되어 엉뚱한 위반을 성공으로 오판한다. 필요해지면 제약 이름으로 좁히는 오버로드를 추가한다.
6. **UNIQUE 위반인데 승자 행이 없으면 판정 불가다** — 삼키지 말고 원래 예외를 던지고 `ERROR` 로 남긴다.
7. **로그 컨텍스트에는 마스킹된 값을 넘긴다**(⑤). 원문 노출이 곤란한 식별자는 값 객체가 이미 막고 있다.

**언제 쓰지 않는가** — `UNIQUE` 로 표현되는 불변식("이 행은 하나뿐") 전용이다. 잔량 증감처럼
**행이 이미 있는 상태의 불변식**("음수가 되지 않는다")은 이 골격이 아니라 **조건부 UPDATE**
(`... WHERE balance > 0`)로 지킨다. 판정 조건을 갱신문 자체에 넣는 것이 요점이라는 점은 같다.

- `UniqueRace` 는 `shared/support` 의 **정적 유틸**이고 `@Support` 가 **아니다.**
  `@Support` 는 도메인 모듈의 `internal/service/support` 전용 표식이라(규약 §4, R3) 여기 붙이면
  `ArchitectureConventionTest` 가 잡는다. 상태 없는 골격이므로 빈일 이유도 없다.

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
도메인 모듈(= `internal/service` 보유)에 대해 소스 import 를 스캔해 R1~R7 을 단언한다:

| 규칙 | 잡는 것 |
|---|---|
| R1 | 모듈 루트의 공개 타입은 다른 모듈이 실제로 참조한다 (+ `*Port` 는 루트나 `internal/port` 에만) |
| R2 | `internal/service` 직속은 전부 `*Service`, 각각 `*Port` 를 구현한다 (개수 무제한) |
| R3 | `service/support` 타입은 전부 `@Support`, `@Support` 는 거기에만 |
| R4 | `@Support` 는 같은 모듈의 `*Service` 중 하나만 참조한다 |
| R5 | `handler`(컨트롤러 등)는 `internal/service` 를 직접 참조하지 않는다 — 포트로만 |
| R6 | 구체 `*Service` 는 자기 패키지 밖에서 참조되지 않는다 |
| R7 | `@Support` 에 `@Component` 를 함께 붙이지 않는다 — 메타 `@Component` 라 중복이다 |

- 두 테스트 모두 **CI 필수**다. 배포 워크플로의 `./gradlew test` 에 포함된다.
- 런타임 확인: `/actuator/modulith` (외부에는 Caddy 가 403 으로 막는다).

## 새 모듈 추가 시 만드는 파일

```
{name}/package-info.java
{name}/internal/port/{Name}{책임}Port.java         (기본값 — 자기 모듈 핸들러만 부르는 포트. R1)
{name}/{Name}{책임}Port.java                       (다른 모듈이 실제로 부를 때만 루트로 올린다. R1)
{name}/{Value}.java                               (경계를 넘는 값 객체·타입 ID — 필요할 때만)
{name}/dto/package-info.java                      (@NamedInterface("dto") — 이게 없으면 안 보인다)
{name}/dto/*.java                                 (경계를 넘는 record 만)
{name}/internal/service/*Service.java             (포트 구현 — 직속은 전부 *Service. 여럿 가능. R2)
{name}/internal/service/support/*.java            (@Support 부품 — Service 만 참조. R3·R4·R7)
{name}/internal/handler/inbound/{Name}Controller.java   (HTTP 어댑터 — 포트로만 부른다. R5)
{name}/internal/handler/outbound/repository/{Name}Repository.java
{name}/internal/handler/outbound/client/*.java    (외부 시스템 접점 — 있을 때만)
{name}/internal/entity/{Name}.java
{name}/internal/entity/dto/*.java                 (모듈 내부 전용 record — 밖으로 안 나간다)
src/test/.../{name}/{Name}ControllerTest.java     (REST Docs — 포트를 @MockitoBean)
src/test/.../{name}/{Name}ModuleTest.java         (@ApplicationModuleTest)
src/docs/asciidoc/{name}.adoc
```

`ArchitectureConventionTest`(R1~R7)가 이 골격을 강제한다 — 새 모듈도 자동으로 규약에 걸린다.

- ⚠️ **이 목록은 체크리스트가 아니라 자리표다.** 소비자 없는 포트, 호출자 없는 dto 는 만들지 않는다
  (규약 §1 의 payment 선례). 규약이 요구하는 최소는 **포트 하나 + Service 하나**뿐이다.

도메인 정의서(`docs/new-domain/{name}/{name}-v{n}.md`)를 `/usecase` → `/develop-design` 으로 **먼저** 만들고,
골격은 **이 절의 자리표대로** 구현 첫 커밋에서 직접 만든다.
**파일 배치의 정본은 이 문서다** — 정의서에는 모듈 매핑표를 쓰지 않는다.

## 주석 — 최소로 쓴다 (2026-08-15 채택)

**기본은 주석 없음이다.** 이름과 구조로 말이 되게 쓰고, 그래도 전달이 안 되는 것만 남긴다.

| 남긴다 | 남기지 않는다 |
|---|---|
| 코드를 봐도 알 수 없는 **왜** — 기각된 대안, 비직관적 제약의 근거 | 코드가 이미 말하는 **무엇** |
| 어기면 조용히 깨지는 **계약** — 리플렉션 생성자, 호출 순서 의존, 발행이 코드에 안 보이는 지점 | 설계 논의 재현·결정 날짜·문서 인용 (→ `docs/` 가 정본) |
| 공개 타입·포트의 사용 계약 (한두 줄) | 클래스·메서드마다 기계적으로 다는 javadoc |

- 설계 근거는 **설계서에 쓴다.** 같은 내용을 코드에 옮기면 두 벌이 되고 한쪽이 먼저 낡는다.
- 주석이 코드보다 길면 이름이나 구조가 잘못됐다는 신호다 — 주석을 늘리지 말고 코드를 고친다.
- 기존 파일의 긴 주석을 **선례로 삼지 않는다.** 이 규칙 이전에 쓰인 것이다.

**판정선** — 애매하면 이 셋으로 자른다.

| 선 | 기준 |
|---|---|
| 길이 | 한 주석은 **한 줄**이 기본, 최대 두 줄. 세 줄이 필요하면 설계서로 갈 내용이다 |
| 분량 | **한 파일의 주석 줄 수 < 실코드 줄 수 / 5.** 넘으면 지우거나 설계서로 옮긴다 |
| 대상 | 엔티티 필드·getter·생성자·DTO·설정 클래스에는 **주석을 달지 않는다**. 필드에 남길 만한 건 "이 값이 비면 무엇이 조용히 깨지는가" 하나뿐이다 |

- `@param`·`@return`·`@throws` 는 **쓰지 않는다.** 이름이 말하지 못하면 이름을 고친다.
- 강조 마크업(`<b>`·🔴·⚠️)으로 주석을 키우지 않는다. 강조가 필요할 만큼 중요하면 설계서 항목이다.

## 흔한 실수

| 증상 | 원인 | 해결 |
|---|---|---|
| `verify()` 가 "module not declared" | 최상위 패키지에 `package-info.java` 없음 | 모듈로 만들거나 `shared` 로 옮긴다 |
| 두 모듈이 서로 참조 | 순환 의존 | 한쪽을 이벤트 구독으로 뒤집는다 |
| 엔티티를 다른 모듈에서 쓰고 싶다 | 경계 설계 실패 신호 | 필요한 값만 담은 record 를 모듈 루트에 노출하거나 이벤트로 전달 |
| 다른 모듈 데이터를 키로 저장하고 싶다 | FK 가 필요한 것 | **소유 모듈이 노출한 타입 ID** 를 값 컬럼으로 (→ "타입화된 식별자" 절) |
| 다른 모듈이 `dto/` 를 못 본다 | `@NamedInterface` 미선언 또는 `allowedDependencies` 에 `:: dto` 누락 | 둘 다 채운다 (→ "패키지 레이아웃") |
| 동시 요청 둘이 같은 행을 만든다 | "조회해서 없으면 삽입"을 방어로 쓴 것 | `UNIQUE` 제약 + `UniqueRace` (→ "동시성" 절) |
| UNIQUE 위반을 잡았는데 트랜잭션이 죽어 있다 | 삽입이 호출자와 같은 트랜잭션에서 터졌다 | 삽입을 `REQUIRES_NEW` 쓰기 빈으로 분리 (→ "동시성" 규칙 2) |
| 공용 유틸을 모든 모듈이 참조 | `shared` 가 OPEN 이 아님 | `@ApplicationModule(type = Type.OPEN)` |
