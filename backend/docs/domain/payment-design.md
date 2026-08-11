# Payment 도메인 설계

> 비즈니스 요구사항(유스케이스 명세): [payment.md](./payment.md) — **요구·정책·API 계약의 정본**. 이 문서는 소프트웨어 설계만 다룬다.
> **대응 유스케이스 버전: v1** ← payment.md §9-2 (2026-08-11 결정 17건 전부 확정)
> 서버 계약(정본): `docs/server/api-spec.md` — **아직 존재하지 않는다.** auth 에 이어 이 도메인도 미입주(payment.md §10-10)
> 구조 규칙: [architecture.md](../rule/architecture.md) · 에러: [error-handling.md](../rule/error-handling.md) · 테스트: [testing.md](../rule/testing.md) · REST Docs: [rest-docs.md](../rule/rest-docs.md) · 토스: [toss-integration.md](../rule/toss-integration.md)
> 상태: **설계 확정 (2026-08-11)** — `/implement payment` 호출로 승인. §12 는 아래 결정으로 착수했다:
>   §12-1 auth.md v3 선반영 완료 · **§12-3 recheck 는 "일단 그대로 신뢰" (사용자 결정 08-11 — 출시 전 재결정, payment.md §4-7-1⑧ⓐ 유지)** ·
>   §12-4 권고안 채택(저장 `LocalDateTime`·응답 직렬화 시 `+09:00`) · §12-5 정보성(결정 불요)
> 개정: **2026-08-11 사용자 결정 — 모듈 간 식별을 "해시 복제"에서 "타입화된 기본키 참조"로 전환**(§2-1 쟁점 1).
> 규칙 정본은 [architecture.md](../rule/architecture.md) "타입화된 기본키" 절 · 구현 선례는 `C:\Spring_Study\youngZZ`

> ⚠️ **두 번째 도메인이다.** 선례는 [auth-design.md](./auth-design.md) 하나뿐이고, 이 설계는 그 선례를
> **네 곳에서 의도적으로 벗어난다**: ⓐ 외부 API 를 호출한다 ⓑ 엔티티가 4개이고 한 트랜잭션에서
> 함께 쓰인다 ⓒ 잡는 예외를 `DuplicateKeyException` 으로 좁힌다(auth-design §12-5 권고 반영)
> ⓓ **타입화된 기본키 참조 패턴을 처음 적용한다** — auth 의 `Registration` 계약이 함께 바뀐다(auth-design **v3**).

---

## 1. 목적과 범위

토스 IAP 주문을 **우리 이용권으로 바꾸고**, "지금 쓸 수 있는가"에 답한다.
**요구는 [payment.md](./payment.md) §1~§7 을 따르고 여기 중복 기술하지 않는다.**

**이번 구현 산출물**

- 신규 모듈 `payment` — 상품·지급·이용권·소모·웹훅·STALE 해소 (U1~U14 · §4-7-1)
- 신규 모듈 `bootstrap` — `auth` + `payment` 집계. **auth-design §12-1 이 미룬 것이고, 미룬 사유(`payment` 부재)가 해소됐다**
- `auth` 확장 — **`UserId` 타입 노출**(모듈 루트) + `Registration` 에 `userId` 추가 → **auth-design v3**(§7·§12-1)
- `shared/domain` 확장 — 타입 ID 공통 부모(`ValueObject`·`LongTypeIdentifier`·`LongTypeIdentifierJavaType`) —
  [architecture.md](../rule/architecture.md) "타입화된 기본키" 절이 규칙 정본
- `config/SecurityConfig` 변경 — 공개 경로 **2개** 추가(상품 조회·웹훅. 부트스트랩은 인증 필요라 공개 경로가 아니다)
- `ErrorCode` 에 `PAY` 섹션 7건 추가
- 신규 테이블 4개 + 수동 DDL 1본
- 🔴 **신규 adoc 2본 — 스키마뿐 아니라 클라 SDK 연동 계약(K1~K20)까지 싣는다**(§8-1).
  **결제는 절반이 클라에서 돌아** 필드 표만으로는 계약이 전달되지 않는다

**범위 외**

| 대상 | 사유 |
|---|---|
| **이용 게이트를 실제로 거는 엔드포인트** | 대상이 `subtitle` 작업 생성인데 **그 도메인이 없다**(payment.md ✅-9). 소모 API 는 만들고 **호출자는 그 도메인이 붙인다**(§2) |
| 환불 처리·구독 해지 | 플랫폼에 파트너 API 자체가 없다(payment.md §4-8) |
| 단건 환불 감지 | ✅-8 "감지하지 않음"으로 확정 |
| 쿠폰·무료체험·연간 플랜·결제 이력 화면·관리자 기능 | payment.md §8 백로그 |
| `docs/server/api-spec.md` 신설 | payment.md §10-10 — 이 설계의 산출물이 아니다 |

---

## 2. 도메인 경계

**책임** — `payment` 는 **"이 사용자가 무엇을 쓸 수 있는가"** 에 단일 권위를 갖는다.
그 이상은 알지 않는다: 사용자가 누구인지도, 작업이 무엇인지도 모른다.
**식별에 쓰는 것은 auth 가 노출한 `UserId` 뿐이다 — 익명키를 저장·식별에 쓰지 않는다.**
(익명키 수신은 컨트롤러 1곳뿐이고 즉시 `UserId` 로 해석해 버린다 — §2-1 쟁점 1)

**다른 모듈과의 관계**

| 상대 모듈 | 관계 | 경계 처리 |
|---|---|---|
| `shared` | 사용 (OPEN) | 자유 참조. `BaseTimeEntity`·`ErrorCode`·`BusinessException`·**타입 ID 공통 부모(`shared/domain`)** |
| `config` | 역방향 참조당함 | `SecurityConfig` 가 공개 경로에 웹훅·상품 경로를 연다. **`config` → `payment` 의존은 만들지 않는다** |
| `auth` | **사용** — `allowedDependencies` 명시 | ⓐ **`UserId` 타입 참조**(FK 컬럼·시그니처) ⓑ 컨트롤러가 `AuthService.register` 로 익명키→`UserId` 해석(멱등·자동 등록). **`auth/internal` 은 여전히 불가** — 근거 §2-1 쟁점 1 |
| `bootstrap` | **참조당함** | `bootstrap` → `payment` 단방향. `PaymentService` 직접 호출 |
| `subtitle` (미존재) | **참조당함(예정)** | `subtitle` → `payment` 단방향. 소모 API 를 `UserId`·`UsageTicketId` 로 호출한다. **payment 는 subtitle 을 영원히 모른다** |

- **이벤트를 발행하지 않는다.** [architecture.md](../rule/architecture.md) 는 이벤트를 우선하지만,
  MVP 에 `EntitlementGranted`·`SubscriptionRevoked` 를 구독할 모듈이 **하나도 없다.**
  ✅-17("이용 중단 시 진행 중 작업은 끝까지 처리")이 확정돼 **회수 시 다른 모듈에 알릴 일 자체가 없다.**
  auth-design §2 의 판단("소비자 없는 이벤트는 아웃박스만 채운다")을 그대로 따른다.
- `bootstrap` → `payment` 는 **직접 호출**이다. 진입 응답을 동기로 조립해야 해 이벤트로 대체할 수 없다.

### 2-1. ⚠️ 설계 쟁점

#### 쟁점 1 — payment 는 사용자를 무엇으로 기억하는가 (2026-08-11 사용자 결정 — 패러다임 전환)

**문제.** payment 의 모든 엔티티는 "누구의 것인가"를 저장해야 한다. 후보는 셋이다.

| 안 | 판정 | 이유 |
|---|---|---|
| **익명키 해시를 자체 저장** + `AnonymousKeyHasher` 를 `shared/security` 로 승격 | ❌ **기각 (초안 채택안이었다)** | ⓐ 같은 해시가 **테이블 4개에 복제**되고 `users` 와의 일치를 아무도 보장하지 않는다 ⓑ 해싱이라는 auth 의 권위가 shared 로 새어 나간다 ⓒ **익명키 이관·재출시(payment.md R12) 시 payment 전 행 마이그레이션**이 필요해진다 ⓓ UNIQUE 위반 메시지에 해시가 실린다(원문보다는 낫지만 U14 표면) |
| 원시 `Long` FK | ❌ | "어느 도메인의 Long 인가"가 시그니처에서 사라진다. `ticketId`·`userId` 혼용을 컴파일러가 못 잡는다 |
| **`User` 기본키를 타입화한 `UserId` 를 FK 로 저장** | ✅ **채택** | ⓐ **모듈이 노출하는 것은 엔티티가 아니라 기본키 타입뿐** — `User` 는 `internal/` 에 남는다 ⓑ 해시는 `users` 한 곳에만 존재 — **이관 시 갱신 지점이 한 곳**으로 준다 ⓒ payment 테이블·로그 어디에도 익명키 파생값이 없다 ⓓ 타입이 혼용을 컴파일 타임에 잡는다 |

> 규칙 정본: [architecture.md](../rule/architecture.md) "모듈 간 데이터 참조 — 타입화된 기본키".
> 구현 선례: `C:\Spring_Study\youngZZ` — `Comment.authorId(AnonymousUserId)` 등 전 도메인 검증.
> ⚠️ 초안 채택안(해시 승격)의 기각 근거였던 *"`Registration` 에 식별자가 없어 얻을 것이 없다"* 는
> **auth-design §4 의 결정을 뒤집는 것으로 해소한다** — `Registration` 에 `UserId` 를 담는다(auth-design **v3**, §7).

**Modulith 번역** — youngZZ 는 자유 import 가 되지만 우리는 경계가 강제된다:

- `UserId`·`UserIdJavaType` 은 **auth 모듈 루트**에 둔다 — "모듈 루트 public 타입만 외부에 보인다" 규칙 그대로.
- `payment.allowedDependencies = { "shared", "auth" }`. **auth 는 payment 를 모른다** — 단방향이라 순환 없음.
- 공통 부모(`ValueObject`·`LongTypeIdentifier`·`LongTypeIdentifierJavaType`)는 `shared/domain` — OPEN 모듈.

**익명키 → `UserId` 해석은 누가 하나** — youngZZ 는 웹 계층(ArgumentResolver)에서 하지만, 그 resolver 가
auth 를 참조해야 해서 우리 구조에선 `shared → auth` 역방향 의존이 생긴다(shared 는 최하층).
**payment 의 컨트롤러가 `AuthService.register(anonKey).userId()` 를 직접 부른다** —

- `register` 는 **멱등**이라 몇 번 불러도 안전하고, **부트스트랩을 안 거친 첫 결제 사용자도 자동 등록**된다
  (payment.md §4-10 의 "결제 API 가 결제를 요구할 수 없다"와 모순 없음 — 사용자에게 요구하는 것이 없다).
- ⚠️ **auth-design §6-4 전제(트랜잭션 밖 호출)를 지켜야 한다.** 컨트롤러는 트랜잭션이 없어 양립하지만,
  **`GrantWriter`(REQUIRES_NEW) 안에서 부르면 함정 ④가 되살아난다** — §6-5·§10 이 감시한다.
- `PaymentService` 의 사용자 단위 메서드는 **전부 `UserId` 를 받는다.** 익명키가 payment 모듈에
  들어오는 지점은 컨트롤러 한 곳뿐이고, 저장되는 곳은 없다.

**결과** — payment 와 auth 는 `UserId` 하나로 이어진다. **auth 는 "익명키 ↔ 사용자"에,
payment 는 "사용자 ↔ 이용권"에 각자 단일 권위**를 갖고, 겹치는 데이터가 없다.

#### 쟁점 2 — 토스 왕복을 트랜잭션 안에 넣으면 두 가지가 동시에 깨진다

**문제.** 지급은 `토스 조회 → DB 쓰기` 순서다. 이걸 한 `@Transactional` 로 감싸면:

```
[커넥션 점유]  외부 HTTP 왕복 동안 DB 커넥션을 물고 있다 → 풀 고갈
[30초 예산]    payment.md §4-6 상한이 커넥션 대기까지 포함하게 된다
```

**해결.** payment.md §4-5-1ⓑ 가 확정한 대로 **외부 호출을 트랜잭션 밖에 둔다.**

```
PaymentService.grant()        -- @Transactional 없음 (auth-design §6-4 와 같은 이유 + 위 두 가지)
  ├─ 조회        (자체 TX)
  ├─ 토스 왕복    ← 트랜잭션 바깥
  └─ GrantWriter (REQUIRES_NEW, 여기서만 쓴다)
```

**규칙 정합성** — auth 는 "바깥 트랜잭션이 지키는 것이 없어서" 없앴고, payment 는 **"바깥 트랜잭션이
외부 호출을 감싸면 안 돼서"** 없앤다. 결론은 같지만 근거가 다르므로 §6-2 에 따로 적는다.

#### 쟁점 3 — 웹훅은 익명키 게이트 밖인데, 그냥 열면 누구나 호출한다

**문제.** 토스는 `X-Anonymous-Key` 를 보내지 않는다(payment.md §4-10). `SecurityConfig` 가 default-deny 라
**지금 상태로는 토스 웹훅이 401 로 튕긴다** — payment.md §10-8ⓐ 가 이미 지적한 사실이다.
그렇다고 `permitAll` 만 하면 **누구나 위조 웹훅으로 기간권을 흔들 수 있다**(U11).

**해결.** 경로는 열되 **모듈이 스스로 인증한다.**

```
SecurityConfig:  /api/v1/webhooks/toss/**  → permitAll   (익명키 게이트만 통과)
       ↓
PaymentWebhookController → WebhookAuthenticator.verify(Authorization 헤더)
       ├─ 불일치 → 401 AUTH_002 (BusinessException)  ※ 처리하지 않는다(U11)
       └─ 일치   → 본문 처리 → 204
```

**두 번째 `SecurityFilterChain` 을 만들지 않는다.** 체인이 둘이면 순서·매칭 규칙이 새 실패 지점이 되고,
게이트 정책이 두 곳으로 갈린다. **검증 대상이 헤더 하나뿐**이라 모듈 안에서 끝내는 편이 읽기 쉽다.

⚠️ **위조 웹훅의 최대 피해를 줄이는 것은 인증이 아니라 §5-5 의 "구독을 새로 만들지 않는다"** 규칙이다
(✅-5). Basic Auth 는 1차 방어일 뿐이고, IP 화이트리스트는 인프라 몫이다.

#### 쟁점 4 — 순환 의존을 만들 수 있는 지점

| 위험 | 왜 생기나 | 차단 |
|---|---|---|
| `payment ↔ subtitle` | 소모 실패 시 payment 가 작업을 되돌리려 하면 `payment → subtitle` 이 생긴다 | **되돌림은 `subtitle` 이 `release()` 를 호출해서 한다**(§5-6). payment 는 티켓만 알고 작업을 모른다 |
| `auth ↔ payment` | `payment → auth` 는 이제 **의도된 의존**이다(쟁점 1). 순환은 **auth 가 payment 를 알게 될 때** 생긴다 | **auth 는 payment 를 영원히 참조하지 않는다** — auth-design 의 "auth 는 subscription 을 영원히 참조하지 않는다" 불변식 그대로. `ModularityTest` 가 강제한다 |
| `bootstrap` 순환 | 없음 | `bootstrap` 이 양쪽을 한 방향으로만 참조 |

### 2-2. `bootstrap` 모듈 — auth-design §2-2 설계를 그대로 구현한다

```
POST /api/v1/bootstrap
   └─▶ bootstrap (allowedDependencies = { shared, auth, payment })
          ├─▶ AuthService.register(anonymousKey)       → newUser, registeredAt, userId
          └─▶ PaymentService.entitlementOf(userId)     → 이용권 상태   ← auth 해석 결과를 그대로 넘긴다
```

- **auth 왕복은 1회로 끝난다** — `register` 가 돌려준 `UserId` 를 payment 에 넘기므로
  payment 가 다시 해석하지 않는다.
- ⚠️ **`BootstrapResponse` 에 `userId` 를 싣지 않는다.** auth.md §5-2 확정 계약(HTTP 응답)에 없는 필드다 —
  `UserId` 는 **서버 내부 식별자**이지 프론트 계약이 아니다.

- **자기 저장소를 갖지 않는다.** 엔티티가 생기면 그건 집계가 아니라 새 도메인이다(auth.md §4-7).
- ⚠️ **`@Transactional` 을 붙이지 않는다.** `AuthService.register` 는 트랜잭션 밖에서 호출돼야 하고
  (auth-design §6-4 전제), 붙이는 순간 auth 의 함정 ④가 되살아난다. **집계는 합치기만 한다.**
- ⚠️ **부분 실패를 허용하지 않는다**(auth.md 확정) — 이용권 조회가 실패하면 전체 500.
  다행히 **이 조회는 토스를 부르지 않아**(§5-3) 토스 장애가 부트스트랩에 전파되지 않는다.
- 🔴 **auth.md 가 v2 확정본이라 응답 스키마 확대는 auth v3 를 요구한다** → §12-1.

---

## 3. 엔티티

**4개다. 축이 서로 다르기 때문이고, 하나로 합치면 §6 의 동시성 대책이 전부 한 행에 몰린다.**

**사용자 식별은 전부 `UserId`(auth 노출 타입, BIGINT 매핑) 다** — 익명키 파생값을 저장하는 컬럼이 없다(§2-1 쟁점 1).

```
PaymentOrder                      -- 주문 원장. 멱등(U3)·선점(U4)의 유일한 근거
  id                Long          -- surrogate PK, IDENTITY. 비노출이라 원시 Long (아래 불릿)
  orderId           String(64)    -- 토스 주문 ID (uuid v7 — ✅-12). **UNIQUE**
  userId            UserId        -- 소유자. @JavaType 값 컬럼(user_id BIGINT) — 연관관계 아님
  sku               String(128)   -- 토스 응답의 sku (클라 주장이 아니다 — payment.md §5-4)
  productType       enum          -- CONSUMABLE | SUBSCRIPTION
  createdAt/updatedAt             -- BaseTimeEntity. createdAt = 지급 시각
  UNIQUE (order_id)               -- 🔴 멱등의 근거. 동시 지급 경쟁을 DB 가 최종 판정한다(§6-3)
  INDEX  (user_id)                -- 소유 주문 조회

CreditBalance                     -- 횟수권 잔량. 사용자당 1행
  id                Long
  userId            UserId        -- **UNIQUE**
  balance           int           -- ⚠️ 음수 불가. CHECK 가 아니라 조건부 UPDATE 로 지킨다(§6-4)
  createdAt/updatedAt
  UNIQUE (user_id)

Subscription                      -- 기간권. 구독 주문당 1행
  id                Long
  orderId           String(64)    -- 최초 구독 주문 ID. **UNIQUE**. 웹훅이 이 값으로 찾아온다
  userId            UserId        -- **UNIQUE** — 한 사용자에게 활성 구독은 하나다
  status            enum          -- ACTIVE|EXPIRED|IN_GRACE_PERIOD|ON_HOLD|PAUSED|REVOKED
  expiresAt         LocalDateTime -- 만료 예정. **최초엔 추정값(결제일+31일 — §4-7-1④)**
  autoRenew         boolean
  lastWebhookOccurredAt LocalDateTime  -- 순서 역전 판정용. ⚠️ recheck 는 이 값을 갱신하지 않는다(§4-7-1⑦)
  expiresAtEstimated boolean      -- true = 웹훅이 아직 한 번도 덮지 않았다
  createdAt/updatedAt
  UNIQUE (order_id) · UNIQUE (user_id)

UsageTicket                       -- 소모 예약. ✅-4ⓐ "생성 시 예약 → 완료 확정"의 실체
  id                UsageTicketId -- ⚠️ 타입화된 PK — subtitle 에 노출되는 유일한 payment 식별자
  userId            UserId
  source            enum          -- SUBSCRIPTION(차감 없음) | CREDIT(차감 1)
  status            enum          -- RESERVED | COMMITTED | RELEASED
  createdAt/updatedAt
  INDEX (user_id, status)
```

- [BaseTimeEntity](../../src/main/java/kang20/ytcreator/shared/domain/BaseTimeEntity.java) **전부 상속**한다.
  `PaymentOrder.createdAt` 이 곧 지급 시각이라 별도 컬럼을 만들지 않는다(auth-design §3 과 같은 판단).
- **surrogate PK 를 쓴다.** `orderId` 를 PK 로 삼으면 다른 테이블이 FK 로 `orderId` 를 들고 다니게 되어
  **U14(비노출) 표면이 넓어진다.** 식별자와 PK 를 분리한다.
- **타입화는 노출되는 식별자에만 한다**([architecture.md](../rule/architecture.md) 규칙) —
  `UsageTicket.id` 만 `UsageTicketId`(payment 모듈 루트 노출, subtitle 이 `commit`/`release` 에 쓴다)이고,
  나머지 세 PK 는 밖으로 나가지 않으므로 원시 `Long` 을 유지한다. **전 PK 일괄 타입화는 과한 추상화다.**
- ⚠️ **`Subscription.userId` 도 UNIQUE 다.** 플랜 전환 시 중복 구독 사례가 보고돼 있는데
  (payment.md §8), **우리는 플랜이 하나뿐**이라 두 번째 활성 구독은 정상 상태가 아니다.
  두 번째가 오면 DB 가 거부하고 `PAY_003` 으로 떨어진다 — **조용히 두 개를 갖는 것보다 낫다.**
- **`expiresAtEstimated` 는 진단 정보다** — 웹훅 정본을 한 번이라도 받았는지를 남긴다.
  ⚠️ **(구현 라운드 1 정정)** 초안은 이 필드를 STALE 판정식에 넣었지만 **기각됐다** —
  그러면 정본 수신 **후**의 갱신 유실(매월 반복되는 정상 시나리오)을 STALE 로 못 잡아
  §6-7 이 금지한 "이미 낸 사람에게 결제 유도" 경로가 된다. **판정 트리거는 payment.md §4-7-1③
  원문대로 시간 경과다**: `status == ACTIVE && (expiresAt + 유예 1일) 경과`.

### 3-1. 수동 DDL — **필요하다**

운영은 `ddl-auto: validate` 라 **스키마가 배포로 만들어지지 않는다**(auth-design §3-1).

| 산출물 | 내용 | 적용 시점 |
|---|---|---|
| `backend/deploy/sql/payment-v1.sql` | `payment_orders` · `credit_balances` · `subscriptions` · `usage_tickets` + 인덱스 | **앱 배포보다 먼저** |

- `event_publication` 은 **auth 배포에서 이미 적용됐다**(auth-design §3-1). 다시 만들지 않는다.
- **`user_id`·`usage_tickets.id` 는 전부 `BIGINT`** — 타입 ID 는 자바 쪽 표현일 뿐 DDL 은 원시 타입이다.
  `users` 테이블은 auth-v1 로 이미 존재하므로 **참조 대상의 선행 배포 조건은 이미 충족**돼 있다.
- **물리 `FOREIGN KEY` 를 걸지 않는다** — [architecture.md](../rule/architecture.md) "타입화된 기본키" 절의
  기본 정책. `@ManyToOne` 이 없어 Hibernate 가 FK 를 만들지도 검증하지도 않고(`validate` 는 FK 부재를
  못 잡는다), 무결성은 UNIQUE 제약 + "지급 전 `register` 보장"(§5-2)이 담당한다.
  참조 관계는 DDL 주석(`-- users.id`)으로만 표기한다 — youngZZ 와 같은 기조.
- ⚠️ 테이블명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서 깨지고
  로컬(Windows/H2)에서는 안 드러난다 — auth 구현 라운드 1 실측.
- ⚠️ **`balance` 에 `CHECK (balance >= 0)` 을 걸지 않는다.** 걸면 잔량 부족이 예외로 튀어
  §6-4 의 "0행 반환 → `PAY_001`" 판정과 경로가 갈린다. **음수 방지는 조건부 UPDATE 하나로 일원화**한다.

---

## 4. 모듈 매핑 (Spring Modulith)

**모듈 내부 레이아웃 — `internal/` 을 레이어 서브패키지로 조직한다** (2026-08-11 사용자 결정.
규칙: [architecture.md](../rule/architecture.md) "모듈 내부 레이아웃").
모듈 **경계**는 그대로다 — `internal/` 하위는 몇 단계든 전부 모듈 내부라 `verify()` 에 영향이 없다.

```
payment/
├── PaymentService · 컨트롤러 2 · UsageTicketId(·JavaType)   ← 루트 (Modulith 노출 규칙상 고정)
├── dto/                                                     ← @NamedInterface("dto")
└── internal/
    ├── entity/       엔티티 4 + 상태 enum 2
    ├── repository/   리포지토리 4
    ├── writer/       GrantWriter · SubscriptionApplyWriter   (트랜잭션 쓰기 빈)
    ├── client/       TossOrderClient · TossOrderStatus       (외부 접점 — ✅-11 격리)
    └── support/      SubscriptionGate · WebhookAuthenticator · OrderIdMask · ProductCatalogProperties
```

| 위치 | 산출물 | 공개 여부 |
|---|---|---|
| `payment/package-info.java` | `@ApplicationModule(displayName="결제·이용권", allowedDependencies={"shared", "auth", "auth :: dto"})` — auth 의존의 실체는 `UserId` 참조 + 컨트롤러의 `register` 호출(§2-1 쟁점 1). **(구현 정정)** `Registration` 이 `auth/dto` 에 있어 `@NamedInterface("dto")` 참조가 추가로 필요하다 — Modulith 는 하위 패키지를 자동 노출하지 않는다 | — |
| `auth/dto/package-info.java` · `payment/dto/package-info.java` | **(구현 정정 — 신설)** `@NamedInterface("dto")` — dto 를 다른 모듈이 참조하려면 명시 선언 필요. `bootstrap` 은 `{"shared","auth","auth :: dto","payment","payment :: dto"}` | — |
| `payment/PaymentService.java` | 모듈 공개 API — 지급·조회·소모·재확인. **사용자 단위 메서드는 전부 `UserId` 를 받는다** | **public (모듈 밖 유일 진입)** |
| `payment/UsageTicketId.java` | **타입화된 티켓 PK** — `final class extends LongTypeIdentifier`. subtitle 이 쓸 유일한 payment 식별자 | **public (모듈 루트)** |
| `payment/UsageTicketIdJavaType.java` | Hibernate 매핑 어댑터 | public |
| `payment/PaymentController.java` | `products` · `grant` · `entitlement` · `recheck`. **`AuthService.register` 로 익명키→`UserId` 해석(모듈 내 유일 지점)** | public |
| `payment/PaymentWebhookController.java` | 웹훅 수신 (게이트 밖 — §2-1 쟁점 3) | public |
| `payment/dto/` | `ProductCatalog` · `EntitlementView` · `GrantResult` · `UsageTicketView` · 요청 record | public |
| `payment/internal/entity/` | 엔티티 4 + 상태 enum 2 (`SubscriptionStatus`·`TicketStatus`) | 모듈 밖 참조 불가 |
| `payment/internal/repository/` | 리포지토리 4개 | 모듈 밖 참조 불가 |
| `payment/internal/writer/GrantWriter.java` | **`@Transactional(REQUIRES_NEW)`** 지급 쓰기. **별도 빈이어야 하는 이유는 §6-5** | 모듈 밖 참조 불가 |
| `payment/internal/writer/SubscriptionApplyWriter.java` | **`@Transactional`** 구독 반영 쓰기 — 웹훅 `apply`(§5-4) · recheck `applyFromClient`(§5-5). **별도 빈인 이유는 `GrantWriter` 와 같다** — `PaymentService` 안에서 자기 호출하면 프록시를 우회해 트랜잭션이 안 걸린다 | 모듈 밖 참조 불가 |
| `payment/internal/client/TossOrderClient.java` | 토스 `get-order-status` 호출 (mTLS) | 모듈 밖 참조 불가 |
| `payment/internal/client/TossOrderStatus.java` | 토스 응답 8종 + `resultType` 봉투 → 우리 판정 매핑 | 모듈 밖 참조 불가 |
| `payment/internal/support/WebhookAuthenticator.java` | Basic Auth 검증 (U11) | 모듈 밖 참조 불가 |
| `payment/internal/support/SubscriptionGate.java` | `status` + `expiresAt` + STALE → `accessible` 판정(§4-3·§4-7-1) | 모듈 밖 참조 불가 |
| `payment/internal/support/ProductCatalogProperties.java` | `sku` 설정 바인딩 (`@ConfigurationProperties`) | 모듈 밖 참조 불가 |
| `bootstrap/package-info.java` | `@ApplicationModule(displayName="진입", allowedDependencies={"shared","auth","payment"})` | — |
| `bootstrap/BootstrapController.java` | `POST /api/v1/bootstrap` | public |
| `bootstrap/dto/BootstrapResponse.java` | `record(newUser, registeredAt, entitlement)` — ⚠️ **`userId` 를 싣지 않는다**(§2-2) | public |
| `auth/UserId.java` | **신규** — `final class extends LongTypeIdentifier`. auth 모듈 루트 = 공개(§2-1 쟁점 1) | public |
| `auth/UserIdJavaType.java` | **신규** — Hibernate 매핑 어댑터. payment 엔티티가 쓴다 | public |
| `auth/dto/Registration.java` | **변경** — `record(newUser, registeredAt, userId)` (auth-design **v3**, §7) | 기존 public |
| `shared/domain/ValueObject.java` 외 2 | **신규** — `LongTypeIdentifier`·`LongTypeIdentifierJavaType`. youngZZ 에서 이식(§7) | public (OPEN) |
| `config/SecurityConfig.java` | **변경** — 공개 경로에 상품·웹훅 추가(§7) | 기존 |
| `shared/exception/ErrorCode.java` | **변경** — `PAY` 섹션 7건(§9) | 기존 |

**시그니처 수준**

```
PaymentService                                     -- payment 밖에서 부를 수 있는 전부. 익명키를 받지 않는다
  ProductCatalog products()                        -- U1. 설정에서 읽는다. DB 를 보지 않는다
  GrantResult    grant(UserId userId, String orderId)    -- U2·U3·U4. ⚠️ @Transactional 없음(§6-2)
  EntitlementView entitlementOf(UserId userId)     -- U5. 읽기 전용. 토스를 부르지 않는다
  EntitlementView recheck(UserId userId, SubscriptionSnapshot fromClient)  -- §4-7-1⑥
  UsageTicketView reserve(UserId userId)           -- U6·U7. 없으면 BusinessException(PAY_001)
  void            commit(UsageTicketId ticketId)   -- 작업 성공
  void            release(UsageTicketId ticketId)  -- U8. 작업 실패 → 되돌린다
  void            handleWebhook(String authHeader, WebhookEvent event)  -- U9·U10·U11 (익명키 무관)

PaymentController                                  -- 익명키가 payment 에 들어오는 유일한 지점
  userId = authService.register(anonKey).userId()  -- 멱등·자동 등록. ⚠️ 트랜잭션 밖(auth-design §6-4)
  → PaymentService.{grant|entitlementOf|recheck}(userId, ...)

GrantWriter                                        -- internal. PaymentService 와 반드시 다른 빈(§6-5)
  PaymentOrder grant(UserId userId, String orderId, String sku, ProductType type, LocalDateTime now)
                                                   -- @Transactional(REQUIRES_NEW)
                                                   -- 주문 원장 + (횟수권 +1 | 기간권 생성) 을 한 트랜잭션에
                                                   -- ⚠️ AuthService 를 주입받지 않는다 — §6-5·§10 감시

TossOrderClient                                    -- internal. ✅-11 격리 지점(§2-1·§12-2)
  TossOrderStatus statusOf(String orderId)         -- mTLS. 트랜잭션 밖에서만 호출된다

SubscriptionGate                                   -- 순수 함수. 상태 없음
  boolean accessible(Subscription s, LocalDateTime now)   -- §4-3 개폐표 + 유예 1일
  boolean stale(Subscription s, LocalDateTime now)        -- §4-7-1③
```

- **`PaymentService` 가 모듈 루트의 유일한 서비스 타입**이다. 엔티티·리포지토리·토스 클라이언트는
  `internal/` 이라 Modulith 가 외부 참조를 차단한다.
- **`EntitlementView` 는 `orderId` 를 담지 않는다**(U14). 클라는 SDK 로 직접 얻는다(payment.md §5-7).
- **`reserve()` 는 기간권 사용자에게도 티켓을 발급한다.** `source=SUBSCRIPTION` 이면 차감이 없을 뿐이고,
  호출자(`subtitle`)가 **이용권 종류로 분기하지 않아도 되게** 하기 위해서다. 분기가 호출자로 새면
  §4-3 의 소모 우선순위(기간권 우선)가 두 모듈에 흩어진다.

---

## 5. 비즈니스 로직 (서비스 흐름)

### 5-1. `products` — U1 구현

```
products():                                     -- 트랜잭션 없음. DB 를 보지 않는다
  return ProductCatalog(설정의 oneTime.sku, subscription.sku, MONTHLY, offerId=null)
```

- **가격을 담지 않는다.** SDK `displayAmount` 가 정본이다(payment.md U1).
- `sku` 는 `@ConfigurationProperties("ytcreator.payment")` 로 주입한다 — **코드 상수로 박지 않는다.**
  payment.md §4-11 이 *"가격 변경 절차가 문서에 없다"* 며 하드코딩을 금지했고, `sku` 도 같은 축이다.
- 미노출 상품은 설정을 비우면 `null` 로 내려가고 프론트가 버튼을 숨긴다(payment.md §5-2).

### 5-2. `grant` — U2·U3·U4 구현 (**이 도메인의 심장**)

```
[컨트롤러] userId = authService.register(anonKey).userId()   -- 멱등·자동 등록. 트랜잭션 밖
           → grant(userId, orderId)

grant(userId, orderId):                         -- ⚠️ @Transactional 을 붙이지 않는다(§6-2)

  1. 기존 = orderRepository.findByOrderId(orderId)          -- 자체 TX (읽기)
     기존 있음:
        기존.userId == userId → return 멱등 200 (현재 entitlement)  -- U3. 재요청은 에러가 아니다
        기존.userId != userId → throw PAY_005                       -- U4 선점

  2. 토스 = tossOrderClient.statusOf(orderId)               -- ⚠️ 트랜잭션 밖(§2-1 쟁점 2)
     resultType != SUCCESS               → PAY_006 (502)
     status ∈ {PURCHASED, PAYMENT_COMPLETED} → 계속           -- ✅-1
     status == ORDER_IN_PROGRESS         → PAY_002 (409)
     status ∈ {FAILED, REFUNDED}         → PAY_003 (409)
     status ∈ {NOT_FOUND, MINIAPP_MISMATCH} → PAY_004 (404)
     status == ERROR                     → PAY_006 (502)

  3. productType = 토스.sku 로 판별                          -- 클라 주장을 믿지 않는다
     sku 가 우리 카탈로그에 없다 → PAY_004                    -- 남의 상품이다

  4. try:
        grantWriter.grant(userId, orderId, sku, type, now)  -- REQUIRES_NEW. 여기서만 쓴다
     catch DuplicateKeyException:                            -- 경쟁에서 졌다(§6-3)
        경쟁자 = findByOrderId(orderId)                       -- 자체 TX → 새 스냅샷
        경쟁자.userId == userId → 멱등 200
        경쟁자.userId != userId → PAY_005

  5. return GrantResult(granted=true, type, entitlementOf(userId))  -- ⚠️ 커밋 이후에 만든다(§4-5-1ⓒ)
```

- **U14 준수**: `PAY_00x` 어느 것도 `orderId` 를 본문에 싣지 않는다. **애플리케이션 로그·예외 메시지도**
  마스킹한다(§9). ⚠️ 단 **프레임워크 로그의 잔존 노출 1건은 §6-6 에서 의도적으로 수용**한다.
- **이 멱등 구조가 곧 U12(미결 주문 복원 지원)의 구현이다** — 복원은 별도 엔드포인트가 아니라
  `grant` 재사용이고(payment.md §6-3), 그게 가능한 근거가 재요청 200 이다.
- **2 를 1 보다 뒤에 두는 이유**: 이미 지급된 주문이면 토스를 부를 필요가 없다.
  복원 흐름(U12)은 **이미 지급된 주문을 다시 보내는 것이 정상**이라 이 경로가 자주 탄다.
  분당 3,000 QPM(미니앱 합산) 을 아끼는 실질 효과가 있다.
- ⚠️ **`PAYMENT_COMPLETED` 를 지급 대상으로 본다**(✅-1). 문서 근거가 없는 확정이므로
  **이 분기를 한 줄로 몰아 놓는다** — 뒤집힐 때 고칠 곳이 한 곳이다(§12-2).

### 5-3. `entitlementOf` — U5 구현

```
entitlementOf(userId):                          -- 읽기 전용. ⚠️ 토스도 auth 도 부르지 않는다
  credits = creditRepository.findByUserId(userId).map(balance).orElse(0)
  sub     = subscriptionRepository.findByUserId(userId)   -- 없으면 status=NONE

  stale       = gate.stale(sub, clock.now())
  accessible  = gate.accessible(sub, clock.now()) || credits > 0
  return EntitlementView(accessible, credits, stale, sub)
```

- **토스를 부르지 않는 것이 계약이다.** 부트스트랩은 부분 실패를 허용하지 않으므로(§2-2),
  여기서 외부를 부르면 **토스 장애가 진입 자체를 막는다**(payment.md §5-3).
- **auth 도 부르지 않는다** — `UserId` 해석은 호출자 몫이다(부트스트랩은 `register` 결과를,
  컨트롤러는 자기 해석 결과를 넘긴다). **auth 장애의 전파 경로가 해석 지점 하나로 고정**된다.
- **`accessible` 은 서버가 계산해 내려준다.** 프론트가 정책을 대신 판단하면 게이트와 갈린다.
- 이용권이 없어도 **404 가 아니라 200** — "없음"은 정상 상태다.

### 5-4. `handleWebhook` — U9·U10·U11·U13 구현

```
handleWebhook(authHeader, event):
  0. authenticator.verify(authHeader) 실패 → throw AUTH_002 (401)     -- U11. 처리하지 않는다
  1. event.eventType == callback.registration_verification → return   -- U10. 204 만 답한다
  2. sub = subscriptionRepository.findByOrderId(event.orderId)
     없음 → 기록만 하고 return                                        -- ✅-5. 웹훅으로 만들지 않는다
  3. event.occurredAt <= sub.lastWebhookOccurredAt → return           -- 순서 역전·중복(U9)
  4. applyWriter.apply(sub.id, event)                                 -- @Transactional
        status/expiresAt/autoRenew ← event.subscription.current       -- 판정 기준은 current.status
        expiresAtEstimated = false                                    -- 정본이 왔다
        lastWebhookOccurredAt = event.occurredAt
        previous 가 우리 상태와 불일치 → WARN 로그 (유실 감지)          -- 보정은 §5-5
  5. 어떤 경우에도 204                                                 -- 반영 실패해도 수신은 성공
```

- **U13(환불·회수 반영)은 이 흐름이 구현한다** — `current.status = REVOKED` 가 즉시 회수다.
  단건 환불 감지는 ✅-8 로 "하지 않음" 확정이라 **U13 의 구현 범위는 구독 웹훅뿐**이다.
- **(구현 명문화) 웹훅 null 방어 3건 — "정본을 무로 덮지 않는다"**: ⓐ `current.autoRenew == null`
  → 기존 값 유지 ⓑ `current.expiresAt == null` → 기존 값 유지(구매 직후 null 사례가 보고된 필드다 —
  payment.md §3-2) ⓒ `occurredAt == null` → `lastWebhookOccurredAt` 미덮어쓰기.
  셋 다 "필드 하나가 비어 왔다"가 "상태를 지워라"를 뜻하지 않기 때문이다.
- **`changeReason` 으로 분기하지 않는다.** 12종에 1:1 로 로직을 붙이면 미문서화 값이 추가될 때 깨진다.
  `changeReason` 은 **로그에만 남기고**, 반영은 `current.status`·`current.expiresAt`·`current.autoRenew`
  세 값으로 한다 — `RESTARTED` 인데 `autoRenew=false` 인 사례가 보고된 이유가 이것이다.
- **`accessGranted` 를 쓰지 않는다** — 산식이 문서에 없다(payment.md §4-3).
- ⚠️ **4 의 실패가 5 를 막지 않는다.** 재전송 정책이 문서에 없어 재시도에 기댈 수 없으므로,
  반영 실패는 **로그로 남기고 204** 를 답한다(payment.md §4-7).
- ⚠️ **`occurredAt` 은 timezone 이 없다.** 수신 즉시 KST 로 해석해 `LocalDateTime` 으로 저장한다(✅-7).

### 5-5. `recheck` — §4-7-1⑥ 구현

```
recheck(userId, fromClient):
  sub = subscriptionRepository.findByUserId(userId)
  없음 → throw PAY_004                          -- 구독 이력이 없는데 재확인할 게 없다
  gate.stale(sub, now) 아님 → 현재 entitlement 반환(200)   -- 멱등. 이미 해소됐다

  applyWriter.applyFromClient(sub.id, fromClient)          -- @Transactional
     status/expiresAt/autoRenew ← fromClient
     expiresAtEstimated = false
     ⚠️ lastWebhookOccurredAt 은 건드리지 않는다             -- §4-7-1⑦
  return entitlementOf(userId)
```

- 🔴 **`fromClient` 는 클라가 보낸 값이다**(§4-7-1⑧ⓐ). **결제 없이 `{status:"ACTIVE"}` 만 보내도
  통과한다.** 알고 여는 구멍이며 출시 전에 닫아야 한다 → §12-3.
- **`lastWebhookOccurredAt` 을 갱신하지 않는 것이 이 설계의 급소다.** 갱신하면 뒤늦게 도착한 웹훅이
  "과거 이벤트"로 판정돼 버려지고, **클라가 보낸 값이 영구히 정본이 된다.**
  건드리지 않으면 웹훅이 recheck 결과를 정상적으로 덮는다 — **웹훅이 정본, recheck 는 임시 보정.**

### 5-6. `reserve` / `commit` / `release` — U6·U7·U8 구현

```
reserve(userId):                                -- @Transactional
  sub = findByUserId(userId)

  gate.stale(sub, now)      → throw PAY_007 (403)        -- 상태 미확인. recheck 유도
  gate.accessible(sub, now) → 티켓(SUBSCRIPTION) 저장 후 반환   -- ✅-4ⓒ 기간권 우선. 차감 없음

  차감 = creditRepository.decrementIfPositive(userId)    -- 조건부 UPDATE. 0행이면 부족(§6-4)
  차감 == 0 → throw PAY_001 (403)                        -- 결제 유도
  티켓(CREDIT) 저장 후 반환

commit(ticketId):                               -- @Transactional. ticketId 는 UsageTicketId
  RESERVED 아니면 무시(멱등)  →  status = COMMITTED

release(ticketId):                              -- @Transactional. U8
  RESERVED 아니면 무시(멱등)
  source == CREDIT → creditRepository.increment(userId)  -- 되돌린다
  status = RELEASED
```

- **`PAY_007` 을 `PAY_001` 보다 먼저 본다.** 순서가 바뀌면 STALE 인 유료 사용자가 `PAY_001`(결제 유도)를
  받는다 — payment.md §6-7 이 금지한 바로 그 화면이다.
- **`commit`·`release` 는 멱등이다.** 작업 도메인이 재시도할 수 있고, 중복 `release` 가 `+1` 을
  두 번 하면 **무료 이용권이 샌다.** 상태 전이로 막는다(§6-4).
- ⚠️ **이미 소모한 횟수권을 환불로 되돌리지 않는다**(payment.md §4-8). `release` 는 **작업 실패**에만 쓴다.

---

## 6. 동시성 제어

### 6-1. 불변식과 경쟁 시나리오

**불변식 3개**

1. 한 `orderId` 로 지급되는 이용권은 **정확히 한 번**이다 (U3 멱등)
2. `CreditBalance.balance` 는 **절대 음수가 되지 않는다** (payment.md §4-8)
3. 한 `UsageTicket` 은 **한 번만** 확정되거나 해제된다

| # | 시나리오 | 발생 경로 | 요구 결과 |
|---|---|---|---|
| **C1** | 같은 `orderId` 로 **동시 2회** 지급 | 🔴 **정상 경로다** — 콜백과 복원(§6-3)이 겹친다. 네트워크 재시도도 |
| **C2** | 같은 사용자(`UserId`)가 **다른 두 주문**을 동시 지급 | 단건 2건 연속 구매 · 복원이 여러 건을 병렬 처리 | 잔량 +2. **행 생성 경쟁이 있다** |
| **C3** | 잔량 1에 **동시 2건** 작업 생성 | 🔴 **멱등이 못 덮는 축**(payment.md §4-5-1) — 더블탭·병렬 요청 | 한쪽만 성공, 잔량 0. **음수 금지** |
| **C4** | 같은 티켓에 `release` **2회** | 작업 도메인의 재시도 | `+1` 은 **한 번만** |
| **C5** | 웹훅 **중복·순서 역전** | 이벤트 ID 가 없다(payment.md §4-7) | 과거 이벤트는 무시 |
| **C6** | 지급과 웹훅 `CREATED` 가 **동시** | 최초 구독 직후 | 구독 1행. 웹훅이 정본으로 덮는다 |

- **C1 은 반드시 일어난다.** payment.md §4-5 가 정상 경로 3개를 열거했고, 그중 하나(복원)는
  **앱 기동마다 도는 흐름**이다.

### 6-2. 함정 분석 — 왜 단순한 방법이 안 되는가

**함정 ①: "조회 후 없으면 삽입"은 C1 을 못 막는다.**
두 요청이 같은 순간 `findByOrderId` 에서 빈 결과를 보고 둘 다 지급한다. **횟수권이 +2 가 된다** —
UNIQUE 제약이 한쪽을 죽여도, 죽기 전에 이미 잔량을 올렸다면 늦다.
→ **그래서 §3 의 `UNIQUE(order_id)` 와 잔량 증가가 반드시 같은 트랜잭션이어야 한다**(§6-5).

**함정 ②: 같은 트랜잭션에서 제약 위반을 잡으면 못 살린다.**
JPA 는 제약 위반 시 트랜잭션을 rollback-only 로 표시한다. 잡아서 재조회해도 커밋에서
`UnexpectedRollbackException` 이 터진다 — auth-design §6-2 가 실측한 그대로다.

**함정 ③: 바깥을 `@Transactional` 로 감싸면 재조회가 거짓말한다.**
MySQL `REPEATABLE READ` 는 첫 읽기에서 스냅샷을 고정하므로, 경쟁자가 커밋했어도 재조회가 "없음"을 본다.
⚠️ **H2 기본 격리는 `READ COMMITTED` 라 테스트에서는 통과하고 MySQL 에서만 터진다.**
→ auth 와 **동일한 결론**: 바깥 트랜잭션을 만들지 않는다.

**함정 ④(payment 고유): 바깥 트랜잭션은 외부 호출까지 감싼다.**
auth 에는 없던 문제다. 토스 왕복이 트랜잭션 안에 들어가면 **커넥션을 물고 네트워크를 기다린다.**
30초 예산(payment.md §4-6)과 커넥션 풀이 **동시에** 무너진다 → §2-1 쟁점 2.

**함정 ⑤: 잔량 차감을 "읽고 → 빼고 → 저장"으로 하면 C3 이 통과한다.**
두 요청이 `balance=1` 을 읽고 둘 다 `0` 을 쓴다. **작업은 2건 돌고 돈은 1건만 받았다.**
낙관적 락(`@Version`)을 붙이면 한쪽이 예외를 받지만, **그 예외를 `PAY_001` 로 번역하는 코드가
또 필요하고 재시도 루프가 붙는다.**

**함정 ⑥: `release` 를 무조건 `+1` 로 만들면 무료 이용권이 샌다.**
작업 도메인이 재시도하면 같은 티켓으로 두 번 부른다. C4 다.

### 6-3. 대안 비교와 채택 — C1(지급 멱등)

| 방식 | 판정 | 이유 |
|---|---|---|
| 조회 → 없으면 삽입 | ❌ | 함정 ① — 잔량이 +2 된다 |
| 같은 TX 안에서 예외 catch | ❌ | 함정 ② — rollback-only |
| 바깥 `@Transactional` + 쓰기만 `REQUIRES_NEW` | ❌ | 함정 ③ + **함정 ④(외부 호출을 감싼다)** |
| 비관적 락 / 잠금 테이블 | ❌ | 결제 경로 직렬화. 30초 예산과 정면 충돌 |
| `INSERT ... ON DUPLICATE KEY` (네이티브) | △ | H2(MySQL 모드)와 문법이 갈려 **테스트에서 검증이 안 된다** |
| **바깥 트랜잭션 없음 + 쓰기만 `REQUIRES_NEW` + UNIQUE 위반만 catch** | ✅ **채택** | auth 와 같은 구조. **호출마다 새 스냅샷**이라 정합성이 격리 수준에 의존하지 않는다 |

> **auth 와 다른 점 하나**: 예외를 UNIQUE 위반으로 **좁힌다** — auth-design §12-5 권고의 반영.
> 넓게 잡으면 NOT NULL 위반·길이 초과가 경쟁 처리 분기로 흘러들어 **재조회가 비고 단서 없는 500** 이 난다.
>
> ⚠️ **(구현 라운드 1 실측 정정)** 초안의 *"`DuplicateKeyException` 으로 좁힌다(Spring 표준 변환)"* 는
> **반증됐다** — JPA(Hibernate) 경로에서 UNIQUE 위반은 `DuplicateKeyException` 으로 변환되지 않고
> `DataIntegrityViolationException` 으로만 온다. 그대로 두면 catch 가 영원히 안 잡혀
> **C1 경쟁 패자·복원 재전송이 전부 500** 이었다(라운드 1 테스트가 실물로 잡음).
> **실구현**: `DataIntegrityViolationException` 을 잡되 **원인 체인의 Hibernate
> `ConstraintViolationException.getKind() == UNIQUE`** 일 때만 경쟁 분기로 — 좁히기의 의도는 유지된다.
> NOT NULL·길이 초과가 원본 그대로 나가는 것은 음성 테스트 2본이 고정한다.
> 🟡 **잔존 위험**: `kind == UNIQUE` 매핑의 실측이 H2 뿐이다. **MySQL(1062)에서 중복 INSERT 1회
> 스모크로 실증**해야 한다 → §11 배포 전 실측 항목.

### 6-4. 대안 비교와 채택 — C3(잔량 차감)

| 방식 | 판정 | 이유 |
|---|---|---|
| 읽고 → 빼고 → 저장 | ❌ | 함정 ⑤ — 잔량 1로 작업 2건 |
| `@Version` 낙관적 락 | △ | 동작하나 **예외 번역 + 재시도 루프**가 붙는다. 실패가 "충돌"인지 "부족"인지 구분도 안 된다 |
| `SELECT ... FOR UPDATE` | △ | 동작하나 **행 락을 트랜잭션 끝까지 잡는다.** 얻는 것에 비해 비싸다 |
| **조건부 UPDATE 한 방** | ✅ **채택** | `UPDATE ... SET balance = balance - 1 WHERE user_id = ? AND balance > 0` → **영향 행 수가 곧 판정이다** |

```
decrementIfPositive(userId): int            -- @Modifying JPQL 조건부 UPDATE (구현은 코드 몫)
  balance = balance - 1  WHERE userId = ? AND balance > 0    -- 타입 ID 파라미터 그대로 (JPQL 은 자동 언랩)
  반환: 영향 행 수. 1 = 성공, 0 = 부족
```

- **불변식 2(음수 금지)를 DB 가 원자적으로 지킨다.** 애플리케이션이 읽은 값을 신뢰하지 않는다.
- **JPQL 이라 H2·MySQL 이 동일하게 동작한다** — 네이티브 SQL 을 쓰지 않는 것이 §6-3 과 같은 이유다.
- **"부족"과 "충돌"을 구분할 필요가 없다.** 0행이면 어느 쪽이든 답은 `PAY_001` 이다.
- ⚠️ **`clearAutomatically` 를 켠다.** 안 켜면 같은 트랜잭션의 영속성 컨텍스트가
  옛 `balance` 를 들고 있어 **직후 조회가 차감 전 값을 준다.**

**C4(중복 `release`)** 는 같은 원리를 상태 전이에 적용한다.

```
transition(id, to): int                     -- 같은 방식의 조건부 UPDATE
  status = ?  WHERE id = ? AND status = RESERVED
  반환: 0 = 이미 처리됨 → 잔량을 건드리지 않고 반환
```

**C2(잔량 행 생성 경쟁)** — 같은 사용자의 첫 두 주문이 동시에 오면 `CreditBalance` insert 가 겹친다.

```
grant 의 C2 처리 (PaymentService — 트랜잭션 밖):          -- (구현 정정: 재시도 위치)
  1. grantWriter.grant(...)                               -- REQUIRES_NEW ①
     ① 안: incrementIfExists → 0행이면 insert
     insert 가 UNIQUE(user_id) 위반 → ① 전체가 롤백되고 예외가 밖으로
  2. catch UNIQUE 위반(잔량 행) → grantWriter.grant(...)  -- 새 REQUIRES_NEW ② 로 전체 1회 재호출
     ② 의 incrementIfExists 는 반드시 1행 (경쟁자가 커밋한 행이 보인다)
```

- ⚠️ **(구현 정정)** 초안 의사코드는 증가 헬퍼 **안에서** insert 실패를 잡고 increment 를 재시도했지만,
  그건 **함정 ②(rollback-only) 와 모순**이다 — 같은 트랜잭션 안이라 살릴 수 없다.
  **실구현이 맞다**: `PaymentService` 가 **writer 전체를 새 REQUIRES_NEW 로 정확히 1회 재호출**한다.
  주문 원장 insert 는 ① 에서 롤백됐으므로 ② 가 다시 수행해도 멱등이 깨지지 않고, **원자성(§6-5)도 유지**된다.
- **재시도는 정확히 1회다.** 행은 한 번만 생기므로 ② 의 `incrementIfExists` 는 반드시 성공한다.
  루프를 돌지 않는다. ② 마저 실패하면 안전망 500 — 도달 불가 방어선으로 §10 에 근거를 남겼다.

**C5(웹훅 순서)** — `occurredAt` 비교로 무시한다. 락이 필요 없다. 같은 구독에 대한 웹훅은
토스가 순차 발송한다고 가정하지 않으며, **과거 이벤트를 버리는 것만으로 되감김이 막힌다.**

**C6(지급 ↔ 웹훅 `CREATED`)** — 웹훅은 **모르는 `orderId` 를 무시**하므로(✅-5), 웹훅이 먼저 오면 버려지고
지급이 구독을 만든다. 지급이 먼저면 웹훅이 정상 반영된다. **어느 순서든 구독은 1행**이다.
⚠️ 웹훅이 먼저 온 경우 그 이벤트는 **영구히 유실된다** — 다만 `expiresAt` 은 추정값(+31일)으로 채워지고
다음 웹훅이 덮으므로 **최대 30일 뒤 정합해진다.** §4-7-1 이 바로 이 구간을 위한 설계다.

### 6-5. 채택안 상세 — `GrantWriter` 가 별도 빈이어야 하는 이유

`@Transactional` 은 **프록시 기반**이다. `PaymentService` 안에서 `this.grant(...)` 를 부르면
프록시를 거치지 않아 **`REQUIRES_NEW` 가 걸리지 않고**, UNIQUE 위반이 호출자 트랜잭션을 오염시킨다
(함정 ②). auth-design §6-4 가 `UserWriter` 로 겪은 것과 **동일한 급소**다.

**payment 에는 이유가 하나 더 있다 — 원자성.**

```
GrantWriter.grant()  @Transactional(REQUIRES_NEW)
  ├─ PaymentOrder insert           ← UNIQUE(order_id) 가 여기서 판정된다
  └─ CONSUMABLE  → CreditBalance +1
     SUBSCRIPTION → Subscription insert (expiresAt = now + 31일, estimated=true)
```

**이 둘은 반드시 함께 커밋되거나 함께 롤백돼야 한다.** 주문만 남고 잔량이 안 오르면 **돈을 내고 못 쓰고**,
잔량만 오르고 주문이 없으면 **멱등이 깨져 재요청 때 또 오른다.**
auth 는 단일 행 삽입이라 감쌀 원자성이 없었지만, **payment 는 있다.**

**판정 매트릭스**

| 상황 | ① 조회 TX | ② 토스 | ③ 쓰기 TX | ④ 재조회 TX | 응답 |
|---|---|---|---|---|---|
| 이미 내 주문 | 커밋 (행 있음) | **호출 안 함** | — | — | 200 멱등 |
| 이미 남의 주문 | 커밋 (행 있음) | **호출 안 함** | — | — | `PAY_005` |
| 최초 지급 | 커밋 (없음) | `PURCHASED` | 커밋 | — | 200 신규 |
| 지급 대상 아님 | 커밋 (없음) | `FAILED` 등 | 실행 안 함 | — | `PAY_003` 등 |
| **경쟁에서 짐(내 것)** | 커밋 (없음) | `PURCHASED` | **롤백** | 커밋 | **200 멱등** |
| 경쟁에서 짐(남의 것) | 커밋 (없음) | `PURCHASED` | 롤백 | 커밋 | `PAY_005` |
| 토스 장애 | 커밋 (없음) | `HTTP_TIMEOUT` | 실행 안 함 | — | `PAY_006` |

- **④ 가 반드시 행을 찾는 근거**: InnoDB 는 중복 키 삽입 시 상대 트랜잭션이 끝날 때까지 대기시킨다.
  ③ 이 위반을 받았다는 것은 **경쟁자가 이미 커밋했다**는 뜻이므로, 새 트랜잭션인 ④ 는 그 행을 반드시 본다.
- ⚠️ **이 근거는 UNIQUE 위반일 때만 참이다**(§6-3 실측 정정 반영 — 판별은 원인 체인의
  `ConstraintViolationException.getKind()`). §6-3 에서 예외를 좁힌 이유가 이것이다.
- ⚠️ **`GrantWriter` 는 `AuthService` 를 주입받지 않는다.** `register` 를 REQUIRES_NEW 안에서 부르면
  auth-design §6-4 의 전제(트랜잭션 밖 호출)가 깨져 **함정 ④가 auth 쪽에서 되살아난다.**
  `UserId` 해석은 컨트롤러에서 끝났고(§2-1 쟁점 1), 여기는 이미 해석된 값만 받는다 — §10 이 감시한다.
- **테스트 프로파일 호환성**: `REQUIRES_NEW`·UNIQUE 위반 → `DuplicateKeyException` 변환·JPQL 조건부
  UPDATE 는 전부 Spring/JPA 표준이라 **H2(MODE=MYSQL)와 MySQL 이 동일하게 동작한다.**
  네이티브 SQL 도 격리 수준 가정도 쓰지 않는다.

### 6-6. 의도적으로 수용한 것

- **`grant` 는 원자적이지 않다.** ①·③·④ 가 별개 트랜잭션이라 중간 상태가 외부에 보인다.
  **지켜야 할 원자성은 ③ 안에 있고**(§6-5) 그건 트랜잭션으로 감쌌다.
- **토스를 부른 뒤 쓰기에 실패하면 토스 호출이 낭비된다.** 재시도하지 않고 복원에 맡긴다
  (payment.md §4-6 "붙잡지 않고 false 반환"). 붙잡는 비용이 더 크다.
- **`Subscription` 의 웹훅 반영에 락을 걸지 않는다.** 같은 구독에 웹훅이 동시에 둘 오는 경우를
  `occurredAt` 비교로만 막으므로, **정확히 같은 `occurredAt` 의 서로 다른 이벤트**는 한쪽이 유실될 수 있다.
  이벤트 ID 가 없는 이상 완전한 판별은 불가능하다(payment.md §4-7) — **수용한다.**
- **`UsageTicket` 이 무한히 쌓인다.** 정리 배치를 만들지 않는다. MVP 트래픽에서 문제가 되지 않고,
  **소모 이력은 U8 분쟁 대응에 쓸모가 있다.** 보관 정책은 운영 데이터가 쌓인 뒤 정한다.
- **C1 경쟁 패자의 UNIQUE 위반 시 Hibernate WARN 에 `orderId` 원문이 실린다** — auth-design §3-2 가
  실측한 것과 같은 프레임워크 로그라 애플리케이션 마스킹(§9)이 닿지 않는다. **수용한다**:
  그 시점의 주문은 **이미 지급·귀속이 끝난 뒤**라(승자가 커밋했으므로 위반이 났다) 로그의 `orderId` 로
  할 수 있는 것이 재요청(멱등 200) 또는 `PAY_005` 뿐이다 — **U14 가 막으려는 선점 가로채기 가치가 없다.**
  auth 처럼 해시로 바꾸면 토스 API 호출·웹훅 대조가 전부 원문 요구라 성립하지 않는다.

---

## 7. 기존 코드 리팩터링

**전수 목록.**

| 파일 | 변경 | 영향 |
|---|---|---|
| [AnonymousKeyHasher.java](../../src/main/java/kang20/ytcreator/auth/internal/AnonymousKeyHasher.java) | **변경 없음이 정책** (§2-1 쟁점 1) | 초안의 `shared/security` 승격이 **취소됐다.** 해싱은 auth 의 권위로 남고, payment 는 해시를 아예 다루지 않는다. `AnonymousKeyHasherTest` 도 제자리 |
| [AuthService.java](../../src/main/java/kang20/ytcreator/auth/AuthService.java) | `register` 의 세 반환 지점(기존·신규·경쟁 패자)에 **`UserId` 를 실어 반환** | 추가 쿼리 없음 — 기존·경쟁 패자는 이미 조회한 행에서, 신규는 `saveAndFlush` 라 채번이 보장된 `id` 에서 꺼낸다. **auth-design v3 의 실체** |
| [Registration.java](../../src/main/java/kang20/ytcreator/auth/dto/Registration.java) | `record(newUser, registeredAt)` → **`record(newUser, registeredAt, userId)`** | 🔴 javadoc 의 *"PK 를 담지 않는다"* 결정이 **정면으로 번복된다** — 근거를 *"원시 Long 대신 타입화한 `UserId` 로 담는다"* 로 교체(auth-design v3 §4) |
| [User.java](../../src/main/java/kang20/ytcreator/auth/internal/User.java) | **`getId()` 게터 추가** (현재 없다) | `@Id` 는 **원시 `Long` 을 유지**한다 — youngZZ 처럼 `@JavaType` 로 타입화할 수도 있지만(가능함이 검증됨) **기구현·기배포 코드의 churn 대비 이득이 없다.** 경계(Registration)에서만 `UserId` 로 래핑. `users` DDL 무변경 |
| [package-info.java (auth)](../../src/main/java/kang20/ytcreator/auth/package-info.java) | javadoc 갱신 — 공개 타입에 `UserId`·`UserIdJavaType` 추가 | *"공개 타입은 AuthService 와 Registration 뿐"* 서술이 낡는다 |
| [AuthServiceTest.java](../../src/test/java/kang20/ytcreator/auth/AuthServiceTest.java) | `Registration` record components 단언 갱신 + **`userId == 저장 행의 id`** 단언 추가 | 🔴 **현재 `containsExactly("newUser","registeredAt")` 단언이 즉시 빨개진다** — 설계 감시자가 정상 작동하는 것이다. 테스트·javadoc·auth-design §4 를 **한 커밋에서** 함께 고친다 |
| [SecurityConfig.java](../../src/main/java/kang20/ytcreator/config/SecurityConfig.java) | `PUBLIC_PATHS` 에 **`/api/v1/payments/products`** 와 **`/api/v1/webhooks/toss/**`** 추가 | ⚠️ **웹훅을 빠뜨리면 토스 웹훅이 401 로 튕겨 U9 가 통째로 죽는다**(payment.md §10-8ⓐ). 웹훅은 permitAll 이지만 **모듈이 Basic Auth 로 다시 막는다**(§2-1 쟁점 3) |
| [ErrorCode.java](../../src/main/java/kang20/ytcreator/shared/exception/ErrorCode.java) | `PAY` 섹션 7건 추가 (§9) | 기존 코드 무변경 |
| [GlobalExceptionHandler.java](../../src/main/java/kang20/ytcreator/shared/exception/GlobalExceptionHandler.java) | **변경 없음이 정책** | ✅ **502 핸들러 신설이 불필요하다.** `handleBusiness` 가 `code.getStatus()` 를 그대로 쓰므로 `ErrorCode` 에 `BAD_GATEWAY` 를 넣으면 자동 처리된다 — **payment.md §7 의 "신설 필요" 우려는 기우다** |
| [ControllerTest.java](../../src/test/java/kang20/ytcreator/base/ControllerTest.java) | **변경 없음이 정책** | `@Import(SecurityConfig.class)` 라 슬라이스 테스트가 default-deny 를 받는다. **payment 컨트롤러 테스트는 익명키 헤더를 붙여야 200 이 난다** — auth-design §7 이 남긴 선례를 그대로 따른다 |
| [common.adoc](../../src/docs/asciidoc/common.adoc) | 시간대 절 확인 | `expiresAt` 이 **사용자에게 보이는 첫 시각**이다 → §12-4 |
| [index.adoc](../../src/docs/asciidoc/index.adoc) | `payment.adoc`·`bootstrap.adoc` include 추가 | **이번엔 실제로 스니펫이 생긴다** — auth 와 달리 컨트롤러가 있다 |
| [toss-integration.md](../rule/toss-integration.md) | 3건 정정 (payment.md §10-6) | ⓐ 서버용 구독 조회 API 부재 명시 ⓑ *"hash 인증으로 인앱결제도 호출"* → **IAP 는 mTLS + `orderId` 뿐** ⓒ 단건 흐름 추가 |
| [CLAUDE.md](../../../CLAUDE.md) | *"인앱결제가 익명키만으로 지원된다"* 정정 (payment.md §10-7) | **IAP 는 `x-anon-key` 를 쓰지 않는다** |
| [auth.md](./auth.md) | **v3 로 올린다** — §4-2 공개 목록에 웹훅·상품 경로, §5-2 부트스트랩 스키마 확대 | 🔴 **v2 확정본이라 조용히 못 고친다** → §12-1 |
| [auth-design.md](./auth-design.md) | **v3 로 올린다** — §4(`Registration` 에 `userId`·`UserId` 신설), §3-2 마지막 불릿(해시 저장 선례 예고) 정정, §2-2(subscription 키 방식 미결) 확정 표기, §6-5(`register` 호출자 목록) 갱신, §13 v3 행 | 설계 정본 역반영. **"id 를 담지 않는다" 결정 번복의 정본 지점** |

- **삭제된 요구가 남긴 코드는 없다.** payment 는 신규 도메인이다.
- ⚠️ **auth 코드·테스트 변경(위 6개 행)은 `feat(auth)` 단독 커밋**으로 분리하고, 그 커밋에서
  `./gradlew test` 그린을 확인하고 넘어간다 — payment 구현과 섞지 않는다(§11).
- ⚠️ **auth-design §6-5 의 재검토 조항이 발동된다** — *"호출자가 늘면 재검토한다"*.
  `register` 의 호출자가 `{bootstrap}` → `{bootstrap, payment 컨트롤러}` 로 늘었다.
  **판단: 문서 + 경계 테스트 유지** — 둘 다 트랜잭션 없는 계층이라 전제가 지켜지고,
  `PaymentTransactionBoundaryTest`(§10)가 `GrantWriter` 의 auth 미주입까지 감시한다. 런타임 가드는 여전히 과하다.

---

## 8. API 계약 · REST Docs

계약 상세는 [payment.md](./payment.md) §5 가 정본. 여기는 **스니펫 매핑만** 둔다.

| 메서드 | 경로 | 인증 | 스니펫 ID |
|---|---|---|---|
| GET | `/api/v1/payments/products` | ❌ 공개 | `payment-products` |
| POST | `/api/v1/payments/grant` | `X-Anonymous-Key` | `payment-grant` · `payment-grant-fail-in-progress`(PAY_002) · `payment-grant-fail-not-purchased`(PAY_003) · `payment-grant-fail-not-found`(PAY_004) · `payment-grant-fail-owned-by-other`(PAY_005) · `payment-grant-fail-upstream`(PAY_006) |
| GET | `/api/v1/payments/entitlement` | `X-Anonymous-Key` | `payment-entitlement` · `payment-entitlement-stale` |
| POST | `/api/v1/payments/subscription/recheck` | `X-Anonymous-Key` | `payment-subscription-recheck` · `payment-subscription-recheck-fail-not-found` |
| POST | `/api/v1/webhooks/toss/payment` | Basic (게이트 밖) | `payment-webhook-verification` · `payment-webhook-status-changed` · `payment-webhook-fail-unauthorized` |
| POST | `/api/v1/bootstrap` | `X-Anonymous-Key` | `bootstrap-entry` · `bootstrap-entry-fail-missing-key`(AUTH_001) · `bootstrap-entry-fail-malformed-key`(AUTH_002) |

- **신규 adoc 2본**: `src/docs/asciidoc/payment.adoc` · `bootstrap.adoc` → `index.adoc` 에 include.
- ✅ **auth-design §8 이 남긴 숙제가 여기서 닫힌다.** 게이트의 401 두 종류를 문서화할 엔드포인트가
  없었는데(auth 는 컨트롤러가 없었다), **`bootstrap-entry-fail-*` 로 실제 문서화된다** —
  auth-design §8 이 채택한 해소 경로 그대로다.
- ⚠️ **성공·실패를 모두 문서화한다.** 실패 응답도 프론트 계약이고, payment 는 실패 분기가
  프론트 행동을 **정반대로** 가른다(`PAY_001` 결제 유도 ↔ `PAY_007` recheck 유도).
- ⚠️ **문서 예시에 실제처럼 보이는 `orderId` 를 쓰지 않는다**(U14). `"order-1"` 같은 명백한 더미를 쓴다.
- 네이밍 규약은 [rest-docs.md](../rule/rest-docs.md).

### 8-1. 🔴 클라이언트가 알아야 하는 것 — **API 산출물에 반드시 기입한다**

**이 도메인은 서버 계약만으로 동작하지 않는다.** 결제의 절반이 클라 SDK 안에서 일어나고
(`processProductGrant` 콜백·미결 복원·`completeProductGrant`), **서버가 모르는 사이에 상태가 갈린다.**
필드 스키마만 문서화하면 **프론트가 흐름을 모른 채 구현하고, 그 결과가 "결제는 됐는데 못 쓰는 사용자"** 다.

> **원칙**: [payment.md](./payment.md) 에 있는 **프론트 책임·SDK 연동 규칙은 전부 `payment.adoc` 본문에
> 옮겨 적는다.** REST Docs 스니펫(필드 표)만으로는 전달되지 않는 계약이기 때문이다.
> ⚠️ **아래 표는 정책을 재기술하지 않는다** — 정책 문장의 정본은 payment.md 이고, 여기는
> **"어느 절을 → 어디에 옮기는가"의 운반 목록**만 둔다. **실제 문구는 adoc 작성 시점에
> payment.md 원문에서 직접 옮긴다** — 이 문서를 거쳐 옮기면 세 벌이 된다.

| # | 옮길 것 (주제만 — 문장은 payment.md 원문에서) | 원문 | **실리는 곳** |
|---|---|---|---|
| **K1** | 가격 표시 규칙 (SDK `displayAmount` 정본 · 하드코딩 금지) | U1·§6-1 | `payment.adoc` §상품 + `payment-products` 필드 설명 |
| **K2** | `productType` → 주문 함수 분기 규칙 | §5-2 | `payment-products` 필드 표 |
| **K3** | 미노출 상품(`null` 키) 처리 | §5-2 | 동일 |
| **K4** | 🔴 `grant` 응답 → 콜백 반환값 규칙 | §5-4·§6-1 | `payment.adoc` §지급 — **응답별 반환값 표** |
| **K5** | 🔴 `false` 의 의미 (결제 취소가 아니다 — 안내 문구 제약) | §6-1 | `payment.adoc` §지급 ⚠️ 박스 |
| **K6** | 🔴 에러 코드별 재시도 가부 | §6-1·§7 | `payment-grant-fail-*` 스니펫마다 **"프론트 행동"** 열 |
| **K7** | 🔴 미결 복원 흐름 전체 (**검수 필수 시나리오**) | §6-3 · U12 | `payment.adoc` §미결 주문 복원 — **흐름도 포함** |
| **K8** | 복원의 실행 시점 제약 (배경 실행) | §6-3 | 동일 |
| **K9** | `completeProductGrant` 반환값 방어 | §6-3 | 동일 |
| **K10** | 🔴 `accessible` 단일 분기 원칙 | §4-3·§5-3 | `payment-entitlement` 필드 표 + ⚠️ 박스 |
| **K11** | 🔴 `subscriptionStale` 의 의미와 프론트 행동 | §4-7-1⑥·§6-7 | `payment-entitlement-stale` 스니펫 + `payment.adoc` §구독 상태 재확인 |
| **K12** | recheck 의 SDK 호출 순서 | §6-7 | `payment.adoc` §구독 상태 재확인 — **흐름도 포함** |
| **K13** | 🔴 `orderId` 비노출과 클라의 확보 경로 | §5-7 · U14 | `payment-subscription-recheck` 요청 필드 설명 |
| **K14** | 🔴 403 두 종류의 구분 (`PAY_001` vs `PAY_007`) | §7·§6-5 | `common.adoc` 에러 표 + `payment.adoc` §이용 게이트 |
| **K15** | 🔴 401 처리 제약 (결제 유도 금지) | §6-5 | `payment.adoc` §이용 게이트 ⚠️ 박스 |
| **K16** | 부트스트랩 실패 처리 제약 | §6-4 | `bootstrap.adoc` ⚠️ 박스 |
| **K17** | 상품별 최소 앱 버전과 버튼 노출 규칙 | §6-2 | `payment.adoc` §상품 |
| **K18** | 결제 중 클라 책임 (미디어 일시정지 · `cleanup()`) — **검수 항목** | §6-1 | `payment.adoc` §지급 |
| **K19** | ⚠️ 결제 유도 화면의 다크패턴 제약 — **출시 불가 사유** | §6-5 | `payment.adoc` §이용 게이트 |
| **K20** | 웹훅과 클라의 무관함 (실시간 통지 없음) | §6-6 | `payment.adoc` §구독 상태 |

**adoc 구성 (이 표를 그대로 절로 만든다)**

```
payment.adoc
├─ §상품            K1 K2 K3 K17
├─ §지급            K4 K5 K6 K18        + payment-grant* 스니펫
├─ §미결 주문 복원   K7 K8 K9            ← 흐름도. 서버 스니펫이 없는 절이지만 반드시 쓴다
├─ §이용권 상태      K10 K20             + payment-entitlement 스니펫
├─ §구독 상태 재확인  K11 K12 K13         + payment-subscription-recheck 스니펫
└─ §이용 게이트      K14 K15 K19         ← 게이트 대상 엔드포인트는 subtitle 몫이지만 규격은 여기 둔다

bootstrap.adoc
└─ §진입            K16                 + bootstrap-entry* 스니펫
```

- ⚠️ **§미결 주문 복원과 §이용 게이트에는 대응 스니펫이 없다.** 전자는 **클라 전용 흐름**이고
  후자는 **대상 엔드포인트가 아직 없다**(§1 범위 외). **그래도 쓴다** — 스니펫이 없다는 이유로
  빠지면 **검수 필수 시나리오가 문서 어디에도 없게 된다.**
- ⚠️ **K4·K6 은 스니펫 필드 표만으로 전달되지 않는다.** `document(...)` 의 응답 필드 설명에
  **"이때 프론트는 무엇을 하는가"** 를 함께 적는다 — 문서를 읽는 사람은 필드가 아니라 행동을 찾는다.

### 8-2. 이 문서가 프론트에 닿는 경로

```
컨트롤러 테스트 통과 → build/generated-snippets/
       ↓
src/docs/asciidoc/{payment,bootstrap}.adoc  (§8-1 의 K1~K20 을 본문에 포함)
       ↓  ./gradlew asciidoctor
docs/api/index.html  (backend 커밋)
       ↓  /docs-sync
main 브랜치 docs/api/index.html   ← 프론트가 읽는 유일한 창구
```

⚠️ **프론트는 `payment.md` 를 읽지 않는다.** backend 브랜치 문서이고 분량도 크다.
**`docs/api/index.html` 에 없으면 프론트에게는 없는 계약이다** — §8-1 이 "반드시 기입"인 이유다.

---

## 9. 에러 코드 (`ErrorCode` 에 `PAY` 섹션 추가)

| enum | 코드 | HttpStatus | 메시지 |
|---|---|---|---|
| `PAY_001` | `PAY_001` | `FORBIDDEN` | 이용 가능한 이용권이 없습니다. |
| `PAY_002` | `PAY_002` | `CONFLICT` | 결제가 아직 확정되지 않았습니다. |
| `PAY_003` | `PAY_003` | `CONFLICT` | 결제가 완료되지 않은 주문입니다. |
| `PAY_004` | `PAY_004` | `NOT_FOUND` | 주문을 찾을 수 없습니다. |
| `PAY_005` | `PAY_005` | `CONFLICT` | 다른 사용자에게 귀속된 주문입니다. |
| `PAY_006` | `PAY_006` | `BAD_GATEWAY` | 결제 정보를 확인하지 못했습니다. |
| `PAY_007` | `PAY_007` | `FORBIDDEN` | 구독 상태 확인이 필요합니다. |

- ✅ **`GlobalExceptionHandler` 에 핸들러를 추가하지 않는다** — `handleBusiness` 가 `getStatus()` 를
  그대로 쓰므로 `BAD_GATEWAY` 도 자동 처리된다(§7).
- ❗ **403 이 두 종류다.** `PAY_001`(결제 유도) vs `PAY_007`(recheck 유도) — 프론트 행동이 정반대다.
  `AUTH_003`(403)은 쓰지 않는다(payment.md §7).
- ⚠️ **`BusinessException` 메시지에 `orderId` 를 넣지 않는다**(U14). 진단이 필요하면
  **payment 자체의 `OrderIdMask.mask()`**(앞 4자)를 쓴다 — `AnonymousKeyFormat.mask` 재사용은
  코드 리뷰 🟡-2 로 기각됐다(익명키 정책과의 결합. 정책의 주인이 다르다).
- 네이밍은 [error-handling.md](../rule/error-handling.md).

---

## 10. 테스트 계획

| 테스트 | 종류 | 핵심 케이스 |
|---|---|---|
| `PaymentGrantTest` | `@ApplicationModuleTest` | 최초 지급(단건 +1 / 구독 생성) · **재요청 200 + 잔량 불변**(U3·**U12** — 복원 재전송이 이 경로다) · 남의 주문 `PAY_005`(U4) · 토스 status 8종 → 응답 매핑 전수 · `sku` 가 카탈로그에 없으면 `PAY_004` |
| `PaymentGrantConcurrencyTest` | **비TX 멀티스레드** | 🔴 **C1** — 같은 `orderId` 동시 N회 → **주문 1행·잔량 정확히 +1**, 예외·500 없음. **`grant` 를 트랜잭션 밖에서 호출해야 실제 경쟁이 재현된다** / **C2** — 같은 `UserId`·다른 주문 N건 동시 → 잔량 정확히 +N (행 생성 경쟁, §6-4) |
| `CreditConsumeConcurrencyTest` | **비TX 멀티스레드** | 🔴 **C3** — 잔량 1에 동시 N건 `reserve` → **정확히 1건만 성공, 나머지 `PAY_001`, 잔량 0. 음수 없음** / **C4** — 같은 티켓 동시 `release` N회 → `+1` 정확히 1회 |
| `PaymentTransactionBoundaryTest` | 단위(리플렉션) | **함정 ③·④ 회귀 방지** — `PaymentService.grant` 에 `@Transactional` 이 **없음**, `GrantWriter.grant` 가 **`REQUIRES_NEW`**, `BootstrapService`(있다면)에 `@Transactional` 없음 + **`GrantWriter` 가 `AuthService` 를 주입받지 않음**(§6-5 — REQUIRES_NEW 안의 `register` 호출 차단)을 단언. **사람이 무심코 붙이는 순간 실패한다** |
| `SubscriptionGateTest` | 단위 | §4-3 개폐표 6종 전수 · **유예 1일 경계값**(`expiresAt`, `+1일`, `+1일 1초`) · STALE 판정(추정값 vs 정본) · 구독 이력 없음 |
| `PaymentWebhookTest` | `@ApplicationModuleTest` | 등록 검증 이벤트 → 204(U10) · Basic 불일치 → 401 **+ 상태 무변경**(U11) · 모르는 `orderId` → 무시 + 구독 미생성(✅-5) · `occurredAt` 역전 → 무시(C5) · `previous` 불일치 → **WARN 로그 + 반영은 진행** · `REVOKED` → 즉시 회수(**U13**) · `AUTO_RENEW_DISABLED` → **만료일까지 유지** · `RESTARTED` + `autoRenew=false` → **`current` 를 따른다** |
| `SubscriptionRecheckTest` | `@ApplicationModuleTest` | STALE → recheck → 게이트 재개방 · **`lastWebhookOccurredAt` 이 갱신되지 않음**(§4-7-1⑦) · **recheck 이후 도착한 과거 웹훅이 정상 반영됨**(위계 검증) · STALE 아닐 때 멱등 200 |
| `PaymentConsumeTest` | `@ApplicationModuleTest` | reserve/commit/release **정상 흐름**(동시성 아님) — 🔴 **STALE + 잔량 보유 사용자 → `PAY_007`(`PAY_001` 아님 — §5-6 판정 순서)** · 기간권 사용자 → `SUBSCRIPTION` 티켓 + **잔량 불변**(✅-4ⓒ) · 이용권 없음 → `PAY_001` · `commit`/`release` 멱등 · `release`(CREDIT) → `+1` |
| `TossOrderClientTest` | 단위(MockRestServiceServer) | `resultType` 7종 → 성공/실패 분류 · **비즈니스 오류가 HTTP 200 으로 오는 경우** · `success.sku` 부재 방어 · 타임아웃 → `PAY_006` |
| `PaymentControllerTest` | `@WebMvcTest` + REST Docs | §8 스니펫 전부. **응답·에러 본문에 `orderId` 가 없음**(U14) |
| `PaymentWebhookControllerTest` | `@WebMvcTest` + REST Docs | 204 · 401 · **반영 실패해도 204** |
| `BootstrapControllerTest` | `@WebMvcTest` + REST Docs | 성공 + **게이트 401 두 종류**(auth-design §8 숙제 해소) |
| `PaymentModuleBoundaryTest` | 구조 | `verify()` 가 못 잡는 불변식: ⓐ `payment.allowedDependencies` 가 **정확히 `{shared, auth}`** — 다른 모듈을 적어 넣으면 `verify()` 는 오히려 정상으로 본다(auth-design §10 선례, **부호가 auth 와 반대**) ⓑ **수동 DDL ↔ 엔티티 매핑 대조**(§3-1) ⓒ payment 가 `subtitle`·**`auth/internal`** 을 참조하지 않음 ⓓ **payment 엔티티에 익명키 원문·해시 컬럼이 없음**(§2-1 쟁점 1 의 감시자) |
| `LongTypeIdentifierJavaTypeTest` | 단위 (`shared/domain`) | youngZZ 테스트 패턴 이식 — `wrap`(Long/null/동일타입/미지원)·`unwrap`·`fromString` 라운드트립 · **리플렉션 생성자 실패 분기**(예외 던지는 픽스처) · `ImmutableMutabilityPlan` · BIGINT 매핑 |
| `UserId`·`UsageTicketId` 계약 | 단위 | 값 동등성·`final` 선언·**public `(Long)` 생성자 존재**(리플렉션 계약 — architecture.md 함정 표) · `toString` 형식 |
| `ModularityTest` | 구조 (기존) | 순환 없음 · `bootstrap` → `auth`·`payment` 단방향 · **`payment` → `auth` 단방향 (역방향 금지)** |

**어떤 테스트가 어떤 라인을 덮는가**

| 산출물 | 덮는 테스트 | 비고 |
|---|---|---|
| `payment/PaymentService.java` | `PaymentGrantTest` + 동시성 2본 + `SubscriptionRecheckTest` + `PaymentConsumeTest` | **catch 분기는 동시성 테스트로만 도달한다** — 목으로 예외를 흉내 내면 §6-5 트랜잭션 경계가 검증되지 않는다 |
| `payment/internal/writer/SubscriptionApplyWriter.java` | `PaymentWebhookTest` + `SubscriptionRecheckTest` | 웹훅 반영 + 클라 반영. `lastWebhookOccurredAt` 불변 단언이 여기 걸린다 |
| `payment/internal/writer/GrantWriter.java` | `PaymentGrantTest` + `PaymentGrantConcurrencyTest` | 정상 커밋 + 경쟁 시 롤백 경계 |
| `payment/internal/client/TossOrderClient.java` | `TossOrderClientTest` | mTLS 조립은 `enabled=false` 로 우회. **조립 자체는 단위 테스트 대상이 아니다** |
| `payment/internal/client/TossOrderStatus.java` | `PaymentGrantTest` + `TossOrderClientTest` | status 8종 × `resultType` 7종 매핑 전수 |
| `payment/internal/support/SubscriptionGate.java` | `SubscriptionGateTest` | 경계값 |
| `payment/internal/support/WebhookAuthenticator.java` | `PaymentWebhookTest` | 일치/불일치/헤더 없음 |
| `payment/internal/repository/` | 해당 서비스 테스트 | 인터페이스 + `@Modifying` 쿼리는 **동시성 테스트가 실제로 덮는다** |
| 엔티티 4개 | `PaymentGrantTest` + `PaymentConsumeTest`(`UsageTicket`) | 생성자·게터·상태 전이 |
| `shared/domain/` 타입 ID 부품 3개 | `LongTypeIdentifierJavaTypeTest` | 리플렉션 분기까지 전 라인 — youngZZ 가 100% 커버 선례 |
| `auth/UserId.java` · `payment/UsageTicketId.java` | 계약 단위 테스트 + 모듈 통합(실 DB 왕복) | ⚠️ **모듈 루트라 `dto/**` 제외에 안 걸린다** — 커버리지 집계 대상. `@JavaType` wrap/unwrap 은 통합 테스트의 flush/clear 후 재조회가 실제로 태운다 |
| `payment/dto/**` | — | `dto/**` 커버리지 제외([testing.md](../rule/testing.md)) |
| `bootstrap/**` | `BootstrapControllerTest` + `ModularityTest` | 컨트롤러 1개 + record |
| `config/SecurityConfig.java` | `PaymentControllerTest`(공개 경로) + `PaymentWebhookControllerTest` | `config/**` 는 커버리지 제외지만 **동작은 반드시 검증한다** — 계약이기 때문 |

- **이번 변경 파일은 라인 100%** 를 채운다. 전역 게이트 수치는 [testing.md](../rule/testing.md) 가 정본이며
  그건 레포 하한일 뿐이다.
- ⚠️ **수용한 검증 한계 3가지**
  1. **구독은 샌드박스가 없다**(payment.md §4-9). 테스트는 *"우리 규칙이 맞게 도는가"* 까지만 답하고
     *"토스와 실제로 맞물리는가"* 는 **실결제가 답한다.** 웹훅 테스트는 **우리가 만든 페이로드**를 넣는다.
  2. **격리 수준 차이 자체는 재현할 수 없다**(H2 `READ COMMITTED` vs MySQL `REPEATABLE READ`).
     §6-3 채택안이 격리 수준에 의존하지 않도록 설계된 이유가 이것이다 —
     `PaymentTransactionBoundaryTest` 가 그 전제의 감시자다.
  3. **mTLS 실호출은 테스트에 없다.** ✅-11(로그인 없이 호출 가능한가)은 **인증서 발급 후 실호출로만**
     판명된다(§12-2).

---

## 11. 구현 단계 (체크리스트)

- [ ] `feat(shared)`: `shared/domain` 타입 ID 공통 부품 3개 이식 + `LongTypeIdentifierJavaTypeTest` (§4·architecture.md)
- [ ] `feat(auth)`: `UserId`·`UserIdJavaType` 신설(모듈 루트) + `Registration` 에 `userId` + `User.getId()`
      + `AuthServiceTest`·javadoc 갱신 (§7). **이 커밋 단독으로 `./gradlew test` 그린을 확인하고 넘어간다**
- [ ] `docs(auth)`: auth-design.md **v3** — §7 의 역반영 목록 (구현 커밋과 같은 라운드에서)
- [ ] `chore(db)`: `backend/deploy/sql/payment-v1.sql` — 테이블 4개 + 인덱스, `user_id BIGINT` + FK 없음 (§3-1)
- [ ] `feat(payment)`: 모듈 골격 + 엔티티·리포지토리 + `UsageTicketId` + `GrantWriter` + `SubscriptionGate` (§4 매핑대로)
- [ ] `feat(payment)`: `TossOrderClient` (mTLS, `enabled=false` 게이트) + `TossOrderStatus` 매핑 (§5-2)
- [ ] `feat(payment)`: `PaymentService` — grant·entitlement·reserve/commit/release (§5-2·5-3·5-6)
- [ ] `feat(payment)`: 웹훅 수신 + `WebhookAuthenticator` + `SubscriptionApplyWriter` + recheck (§5-4·5-5)
- [ ] `feat(payment)`: `PaymentController`·`PaymentWebhookController` + `ErrorCode` PAY 7건 (§9)
- [x] `docs(auth)`: `auth.md` **v3** — §12-1 표 3건. ❗**`feat(bootstrap)` 보다 먼저**(§12-1) — **2026-08-11 설계 승인과 함께 선반영 완료**
- [ ] `feat(bootstrap)`: `bootstrap` 모듈 + 컨트롤러 (§2-2)
- [ ] `feat(payment)`: `SecurityConfig` 공개 경로 2건 추가 (§7)
- [ ] `test(payment)`: §10 전 항목 — **동시성 2본 포함**
- [ ] `docs(api)`: `payment.adoc`·`bootstrap.adoc` 신설 + `index.adoc` include (§8)
      🔴 **§8-1 의 K1~K20 이 본문에 전부 들어갔는지 표로 대조한다.** 스니펫이 없는 두 절
      (§미결 주문 복원 · §이용 게이트)도 반드시 쓴다 — **빠지면 프론트에게는 없는 계약이다**
- [ ] `docs(rule)`: `toss-integration.md` 잔여분(단건 흐름 추가·구독 조회 API 부재 명시) · `CLAUDE.md` 1건 정정 (§7)
- [x] `./gradlew test --tests "*ModularityTest"` — 모듈 경계 통과 (라운드 2)
- [x] **변경 파일 라인 커버리지 100% 확인** → `/code-review` (LINE 98.7 전역 · 미달은 도달 불가 방어선 2곳, §10 근거)
- [ ] 🟡 **배포 전 실측**: MySQL 스테이징에서 중복 INSERT 1회 스모크 — `ConstraintViolationException.getKind() == UNIQUE` 실증(§6-3 잔존 위험. H2 만 실측된 상태)
- [ ] `/docs-sync` — **이번엔 스니펫이 실제로 생긴다**(auth 와 다르다)

---

## 12. 결정 필요 (Open Questions)

> 비즈니스 결정은 [payment.md](./payment.md) §9-1 에서 **17건 전부 확정**됐다. 여기는 **설계 쟁점**만 남긴다.

### 12-1. ✅ `auth.md` v3 — **해소됨 (2026-08-11 사용자 지시로 선반영)**

`bootstrap` 응답에 이용권을 담는 순간 **auth.md §5-2 의 확정 계약이 바뀐다**(payment.md ✅-15ⓐ).
**세 건 모두 반영 완료** — auth.md 는 **v3 확정** 상태다:

| 고친 곳 | 내용 | 상태 |
|---|---|---|
| auth.md §4-2 | 공개 목록에 **웹훅 경로 + 상품 조회 경로** 추가 (각각 "왜 공개인가" 근거 포함) | ✅ |
| auth.md §5-2 | 응답의 이용권 객체를 **`entitlement`**(payment.md §5-3 정본)로 확대. `userId` 비노출 명시 | ✅ |
| auth.md 전반 | 도메인 이름 `subscription` → **`payment`** (JSON 의 `"subscription"` 키는 유지 — payment.md §10-8ⓒ 의 줄 단위 구분 준수) | ✅ |

`feat(bootstrap)` 보다 먼저 올려야 한다는 순서 요건도 충족됐다 — 구현이 확정 문서를 앞지르지 않는다.

### 12-2. ✅-11 이 "불가"로 밝혀지면 무엇이 바뀌는가 — 격리 지점을 미리 고정한다

payment.md ✅-11 은 *"로그인 없이 `get-order-status` 호출 가능"* 으로 확정됐지만
**근거가 가장 약한 항목**이고, mTLS 인증서 발급 직후 실호출 1회로 판명된다.

**설계상 격리**: 토스 호출은 **`TossOrderClient` 한 곳**에서만 일어난다(§4).

| 판명 결과 | 고칠 곳 |
|---|---|
| 가능 (예상) | 없음 |
| `x-anon-key` 로 대체 가능 | `TossOrderClient` 에 헤더 추가 **한 줄**. 🔴 **덤으로 소유권 방어가 선점에서 플랫폼 검증으로 올라간다** — §6-5 의 `PAY_005` 경로가 사실상 죽는다(제거는 하지 않는다) |
| 로그인 필수 = 호출 불가 | 🔴 **U2·U13 이 동시에 사라진다.** §5-2 의 2·3 단계가 통째로 빠지고 **"클라 주장을 믿는다"만 남는다** — 이 도메인의 전제가 무너지므로 **payment.md 부터 다시 연다** |

⚠️ **`TossOrderClient` 를 인터페이스로 두지 않는다.** 구현체가 하나뿐인데 인터페이스를 만들면
과한 추상화다(CLAUDE.md). **클래스 하나에 호출을 모아 두는 것만으로 격리 목적은 달성된다.**

### 12-3. 🔴 recheck 의 클라 값 신뢰 — 출시 전 반드시 닫는다

payment.md §4-7-1⑧ⓐ 가 *"보안 구멍을 알고 여는 것"* 으로 명시한 항목이다.
**결제 없이 `{ status: "ACTIVE" }` 만 보내면 구독이 열린다.**

payment.md 가 제시한 후보 3개 중 **설계 관점의 판단**을 덧붙인다.

| 후보 | 설계 비용 | 평가 |
|---|---|---|
| 유예를 짧게 끊어 반복 연장 (예: recheck 는 `expiresAt` 을 +3일만 연장) | 낮음 — `applyFromClient` 한 곳 | ✅ **권고.** 공격자가 얻는 것이 "3일"로 줄고, **정상 사용자는 다음 웹훅이 오면 자동 정정**된다 |
| 운영 알림 | 낮음 | △ 탐지일 뿐 차단이 아니다 |
| 연장 횟수 상한 | 중간 — 카운터 컬럼 + 리셋 규칙 | △ 상한에 걸린 정상 사용자를 구제할 수단이 **없다**(payment.md §8 — 수동 대응 수단 부재) |

**결정 (2026-08-11, `/implement` 시점 사용자 선택)**: **"일단 그대로 신뢰"** —
payment.md §4-7-1⑧ⓐ 그대로 구현하고 **출시 전에 재결정**한다. 위 후보 비교표는 그때를 위해 남긴다.

**출시 전 재결정 목록 (이 항목과 함께 본다)**

| # | 사실 | 영향 |
|---|---|---|
| ⓐ | recheck 클라 값 신뢰 (위) | 결제 없이 구독 개방 가능 |
| ⓑ | **(코드 리뷰 발견)** `UNIQUE(user_id)` 가 **만료·회수된 구독 행도 막는다** — §3 의 전제("활성 구독은 하나")보다 제약 범위가 넓다 | **이탈 후 재구독 사용자가 결제 성사 뒤(`PURCHASED`) `PAY_003` 을 받고 환불 수단이 없다**(§4-8). MVP 는 첫 구독만 겪지만 **첫 만료가 발생하는 30일 안에** 재구독 처리(기존 행 갱신 or 제약 완화)를 정해야 한다 |

### 12-4. `expiresAt` 의 직렬화 형식 — auth-design §12-3 이 미룬 결정이 여기서 실제로 문제가 된다

auth-design §12-3 은 시각 표기 규약이 **세 곳에서 어긋난다**고 적고 *"사용자에게 보이는 시각이
나오는 순간 이 결정이 화면을 바꾼다"* 며 미뤘다. **`expiresAt` 이 바로 그 시각이다.**

| 출처 | 지시 |
|---|---|
| [rest-docs.md](../rule/rest-docs.md) | UTC ISO-8601 |
| [common.adoc](../../src/docs/asciidoc/common.adoc) | `Asia/Seoul` |
| payment.md §5-3 | `"2026-09-08T00:00:00+09:00"` — **오프셋 포함** |
| `BaseTimeEntity` | `LocalDateTime` — **오프셋 없음** |

- payment.md §5-3 이 *"`expiresAt` 은 타임존을 붙여 내려준다. 프론트에 모호한 값을 넘기지 않는다"* 로
  **이 도메인 한정으로는 이미 답을 냈다**(✅-7 KST).
- 그러나 **전 도메인 공통 규약**은 여전히 미정이고, 여기서 정하면 그대로 선례가 된다.
- **권고**: 저장은 `LocalDateTime`(KST 해석) 유지, **응답 직렬화 시점에만 `+09:00` 오프셋을 붙인다.**
  `docs/server/api-spec.md` 신설(payment.md §10-10) 때 공통 규약으로 승격한다.

### 12-5. JPA Auditing 과 `Clock` — auth-design §12-4 가 미룬 것이 여기서도 걸린다

`expiresAt` 계산(`결제일 + 31일`)은 **`Clock` 빈을 주입받아** 한다 — 테스트에서 고정 가능하다.
그러나 `BaseTimeEntity.createdAt` 은 여전히 JPA Auditing 이 채우고 `Clock` 을 보지 않는다.

- **이번 도메인에서 문제가 되지 않는다** — `expiresAt` 은 우리가 계산하므로 고정할 수 있고,
  `createdAt` 은 응답에 나가지 않는다.
- 다만 **auth-design §12-4 가 예고한 "함께 결정할 시점"이 §12-4(위)와 겹친다.**
  둘 다 시각 문제이므로 `api-spec.md` 신설 때 한 번에 정리할 것을 권한다.

---

## 13. 버전 이력

v1 확정 전이라 없음. 초안 단계 수정은 버전이 아니다(→ [versioning.md](../../../.claude/skills/usecase/references/versioning.md)).
