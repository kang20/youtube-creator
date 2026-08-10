# Payment 도메인 설계

> 비즈니스 요구사항(유스케이스 명세): [payment.md](./payment.md) — **요구·정책·API 계약의 정본**. 이 문서는 소프트웨어 설계만 다룬다.
> **대응 유스케이스 버전: v1** ← payment.md §9-2 (2026-08-11 결정 17건 전부 확정)
> 서버 계약(정본): `docs/server/api-spec.md` — **아직 존재하지 않는다.** auth 에 이어 이 도메인도 미입주(payment.md §10-10)
> 구조 규칙: [architecture.md](../rule/architecture.md) · 에러: [error-handling.md](../rule/error-handling.md) · 테스트: [testing.md](../rule/testing.md) · REST Docs: [rest-docs.md](../rule/rest-docs.md) · 토스: [toss-integration.md](../rule/toss-integration.md)
> 상태: **설계 확정 대기 — 승인 후 구현 착수**

> ⚠️ **두 번째 도메인이다.** 선례는 [auth-design.md](./auth-design.md) 하나뿐이고, 이 설계는 그 선례를
> **세 곳에서 의도적으로 벗어난다**: ⓐ 외부 API 를 호출한다 ⓑ 엔티티가 3개이고 한 트랜잭션에서
> 함께 쓰인다 ⓒ 잡는 예외를 `DuplicateKeyException` 으로 좁힌다(auth-design §12-5 권고 반영).

---

## 1. 목적과 범위

토스 IAP 주문을 **우리 이용권으로 바꾸고**, "지금 쓸 수 있는가"에 답한다.
**요구는 [payment.md](./payment.md) §1~§7 을 따르고 여기 중복 기술하지 않는다.**

**이번 구현 산출물**

- 신규 모듈 `payment` — 상품·지급·이용권·소모·웹훅·STALE 해소 (U1~U14 · §4-7-1)
- 신규 모듈 `bootstrap` — `auth` + `payment` 집계. **auth-design §12-1 이 미룬 것이고, 미룬 사유(`payment` 부재)가 해소됐다**
- `shared/security` 확장 — `AnonymousKeyHasher` 를 `auth/internal` 에서 **승격 이동**(§7)
- `config/SecurityConfig` 변경 — 공개 경로 3개 추가(상품 조회·웹훅·부트스트랩은 인증 필요)
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

**책임** — `payment` 는 **"이 익명키가 무엇을 쓸 수 있는가"** 에 단일 권위를 갖는다.
그 이상은 알지 않는다: 사용자가 누구인지도, 작업이 무엇인지도 모른다. **아는 것은 익명키 해시뿐**이다.

**다른 모듈과의 관계**

| 상대 모듈 | 관계 | 경계 처리 |
|---|---|---|
| `shared` | 사용 (OPEN) | 자유 참조. `BaseTimeEntity`·`ErrorCode`·`BusinessException`·**`AnonymousKeyHasher`(§7 로 이동)** |
| `config` | 역방향 참조당함 | `SecurityConfig` 가 공개 경로에 웹훅·상품 경로를 연다. **`config` → `payment` 의존은 만들지 않는다** |
| `auth` | **없음** | 🔴 **payment 는 auth 를 참조하지 않는다** — 근거 §2-1 쟁점 1 |
| `bootstrap` | **참조당함** | `bootstrap` → `payment` 단방향. `PaymentService` 직접 호출 |
| `subtitle` (미존재) | **참조당함(예정)** | `subtitle` → `payment` 단방향. 소모 API 를 호출한다. **payment 는 subtitle 을 영원히 모른다** |

- **이벤트를 발행하지 않는다.** [architecture.md](../rule/architecture.md) 는 이벤트를 우선하지만,
  MVP 에 `EntitlementGranted`·`SubscriptionRevoked` 를 구독할 모듈이 **하나도 없다.**
  ✅-17("이용 중단 시 진행 중 작업은 끝까지 처리")이 확정돼 **회수 시 다른 모듈에 알릴 일 자체가 없다.**
  auth-design §2 의 판단("소비자 없는 이벤트는 아웃박스만 채운다")을 그대로 따른다.
- `bootstrap` → `payment` 는 **직접 호출**이다. 진입 응답을 동기로 조립해야 해 이벤트로 대체할 수 없다.

### 2-1. ⚠️ 설계 쟁점

#### 쟁점 1 — `payment` 는 `auth` 를 참조하지 않는다. 그런데 해셔가 `auth/internal` 에 있다

**문제.** payment 는 소유권을 **익명키 해시**로 잡는다(§3). 원문 저장은 auth-design §3-2 가 금지했다 —
UNIQUE 위반 메시지에 실려 로그로 새고, payment 는 **UNIQUE 위반을 정상 흐름으로 삼는 설계**(§6)라
auth 보다 발생 빈도가 오히려 높다.

그런데 해시를 만드는 `AnonymousKeyHasher` 가 **`auth/internal/`** 에 있다 — Modulith 가 외부 참조를 차단한다.

**선택지 3개**

| 안 | 판정 | 이유 |
|---|---|---|
| `payment` 가 `auth` 를 `allowedDependencies` 에 넣고 해셔를 쓴다 | ❌ | `internal/` 이라 애초에 불가능하다. `AuthService` 를 뚫어도 **`Registration` 에 식별자가 없어**(auth-design §4) 얻을 것이 없다 |
| `payment` 가 자체 해셔를 복제한다 | ❌ | 알고리즘이 갈리면 **같은 사용자가 두 도메인에서 다른 키를 갖는다.** 조용히 깨지고 테스트로 안 잡힌다 |
| **`AnonymousKeyHasher` 를 `shared/security` 로 올린다** | ✅ **채택** | 해시는 게이트 부품과 같은 성격 — **전 도메인 공통 식별 수단**이지 auth 의 소유물이 아니다 |

> **auth-design §3-2 가 이미 예고한 이동이다** — *"다른 도메인 선례: `subscription` 도 익명키↔주문 매핑을
> 보관한다. 같은 이유로 해시 저장이 맞다 — **이 결정이 그 선례가 된다**"*. 그 시점이 지금이다.

**결과** — `payment.allowedDependencies = { "shared" }`. auth 와 나란히 가장 밑에 놓인다.
**두 도메인은 서로를 모른 채 같은 해시로 같은 사용자를 가리킨다.**

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
| `payment → auth` | 사용자 존재 확인을 하고 싶어진다 | **하지 않는다.** 익명키 게이트를 통과한 요청은 이미 형식이 검증됐고, **결제하려는 사용자에게 등록을 요구할 이유가 없다**(payment.md §4-10) |
| `bootstrap` 순환 | 없음 | `bootstrap` 이 양쪽을 한 방향으로만 참조 |

### 2-2. `bootstrap` 모듈 — auth-design §2-2 설계를 그대로 구현한다

```
POST /api/v1/bootstrap
   └─▶ bootstrap (allowedDependencies = { shared, auth, payment })
          ├─▶ AuthService.register(anonymousKey)          → newUser, registeredAt
          └─▶ PaymentService.entitlementOf(anonymousKey)  → 이용권 상태
```

- **자기 저장소를 갖지 않는다.** 엔티티가 생기면 그건 집계가 아니라 새 도메인이다(auth.md §4-7).
- ⚠️ **`@Transactional` 을 붙이지 않는다.** `AuthService.register` 는 트랜잭션 밖에서 호출돼야 하고
  (auth-design §6-4 전제), 붙이는 순간 auth 의 함정 ④가 되살아난다. **집계는 합치기만 한다.**
- ⚠️ **부분 실패를 허용하지 않는다**(auth.md 확정) — 이용권 조회가 실패하면 전체 500.
  다행히 **이 조회는 토스를 부르지 않아**(§5-3) 토스 장애가 부트스트랩에 전파되지 않는다.
- 🔴 **auth.md 가 v2 확정본이라 응답 스키마 확대는 auth v3 를 요구한다** → §12-1.

---

## 3. 엔티티

**4개다. 축이 서로 다르기 때문이고, 하나로 합치면 §6 의 동시성 대책이 전부 한 행에 몰린다.**

```
PaymentOrder                      -- 주문 원장. 멱등(U3)·선점(U4)의 유일한 근거
  id                Long          -- surrogate PK, IDENTITY
  orderId           String(64)    -- 토스 주문 ID (uuid v7 — ✅-12). **UNIQUE**
  anonymousKeyHash  String(64)    -- 소유자. SHA-256 hex
  sku               String(128)   -- 토스 응답의 sku (클라 주장이 아니다 — payment.md §5-4)
  productType       enum          -- CONSUMABLE | SUBSCRIPTION
  createdAt/updatedAt             -- BaseTimeEntity. createdAt = 지급 시각
  UNIQUE (order_id)               -- 🔴 멱등의 근거. 동시 지급 경쟁을 DB 가 최종 판정한다(§6-3)
  INDEX  (anonymous_key_hash)     -- 소유 주문 조회

CreditBalance                     -- 횟수권 잔량. 익명키당 1행
  id                Long
  anonymousKeyHash  String(64)    -- **UNIQUE**
  balance           int           -- ⚠️ 음수 불가. CHECK 가 아니라 조건부 UPDATE 로 지킨다(§6-4)
  createdAt/updatedAt
  UNIQUE (anonymous_key_hash)

Subscription                      -- 기간권. 구독 주문당 1행
  id                Long
  orderId           String(64)    -- 최초 구독 주문 ID. **UNIQUE**. 웹훅이 이 값으로 찾아온다
  anonymousKeyHash  String(64)    -- **UNIQUE** — 한 사용자에게 활성 구독은 하나다
  status            enum          -- ACTIVE|EXPIRED|IN_GRACE_PERIOD|ON_HOLD|PAUSED|REVOKED
  expiresAt         LocalDateTime -- 만료 예정. **최초엔 추정값(결제일+31일 — §4-7-1④)**
  autoRenew         boolean
  lastWebhookOccurredAt LocalDateTime  -- 순서 역전 판정용. ⚠️ recheck 는 이 값을 갱신하지 않는다(§4-7-1⑦)
  expiresAtEstimated boolean      -- true = 웹훅이 아직 한 번도 덮지 않았다
  createdAt/updatedAt
  UNIQUE (order_id) · UNIQUE (anonymous_key_hash)

UsageTicket                       -- 소모 예약. ✅-4ⓐ "생성 시 예약 → 완료 확정"의 실체
  id                Long
  anonymousKeyHash  String(64)
  source            enum          -- SUBSCRIPTION(차감 없음) | CREDIT(차감 1)
  status            enum          -- RESERVED | COMMITTED | RELEASED
  createdAt/updatedAt
  INDEX (anonymous_key_hash, status)
```

- [BaseTimeEntity](../../src/main/java/kang20/ytcreator/shared/domain/BaseTimeEntity.java) **전부 상속**한다.
  `PaymentOrder.createdAt` 이 곧 지급 시각이라 별도 컬럼을 만들지 않는다(auth-design §3 과 같은 판단).
- **surrogate PK 를 쓴다.** `orderId` 를 PK 로 삼으면 다른 테이블이 FK 로 `orderId` 를 들고 다니게 되어
  **U14(비노출) 표면이 넓어진다.** 식별자와 PK 를 분리한다.
- ⚠️ **`Subscription.anonymousKeyHash` 도 UNIQUE 다.** 플랜 전환 시 중복 구독 사례가 보고돼 있는데
  (payment.md §8), **우리는 플랜이 하나뿐**이라 두 번째 활성 구독은 정상 상태가 아니다.
  두 번째가 오면 DB 가 거부하고 `PAY_003` 으로 떨어진다 — **조용히 두 개를 갖는 것보다 낫다.**
- ⚠️ **`expiresAtEstimated` 가 없으면 STALE 판정이 거짓말한다.** 추정값(+31일)과 웹훅이 준 정본을
  구분하지 못하면, 웹훅을 **한 번도 못 받은 구독**과 **받았지만 만료된 구독**이 같아 보인다.

### 3-1. 수동 DDL — **필요하다**

운영은 `ddl-auto: validate` 라 **스키마가 배포로 만들어지지 않는다**(auth-design §3-1).

| 산출물 | 내용 | 적용 시점 |
|---|---|---|
| `backend/deploy/sql/payment-v1.sql` | `payment_orders` · `credit_balances` · `subscriptions` · `usage_tickets` + 인덱스 | **앱 배포보다 먼저** |

- `event_publication` 은 **auth 배포에서 이미 적용됐다**(auth-design §3-1). 다시 만들지 않는다.
- ⚠️ **`CHAR` 를 쓰지 않는다.** `anonymous_key_hash` 는 길이가 64 로 고정이지만 JPA `String` 매핑의
  기대 타입은 `VARCHAR` 이고, `CHAR` 로 쓰면 `validate` 가 거부한다 — **auth 구현 라운드 3 실측**.
- ⚠️ 테이블명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서 깨지고
  로컬(Windows/H2)에서는 안 드러난다 — auth 구현 라운드 1 실측.
- ⚠️ **`balance` 에 `CHECK (balance >= 0)` 을 걸지 않는다.** 걸면 잔량 부족이 예외로 튀어
  §6-4 의 "0행 반환 → `PAY_001`" 판정과 경로가 갈린다. **음수 방지는 조건부 UPDATE 하나로 일원화**한다.

---

## 4. 모듈 매핑 (Spring Modulith)

| 위치 | 산출물 | 공개 여부 |
|---|---|---|
| `payment/package-info.java` | `@ApplicationModule(displayName="결제·이용권", allowedDependencies={"shared"})` | — |
| `payment/PaymentService.java` | 모듈 공개 API — 지급·조회·소모·재확인 | **public (모듈 밖 유일 진입)** |
| `payment/PaymentController.java` | `products` · `grant` · `entitlement` · `recheck` | public |
| `payment/PaymentWebhookController.java` | 웹훅 수신 (게이트 밖 — §2-1 쟁점 3) | public |
| `payment/dto/` | `ProductCatalog` · `EntitlementView` · `GrantResult` · `UsageTicketView` · 요청 record | public |
| `payment/internal/PaymentOrder.java` 외 3 | 엔티티 | 모듈 밖 참조 불가 |
| `payment/internal/*Repository.java` | 리포지토리 4개 | 모듈 밖 참조 불가 |
| `payment/internal/GrantWriter.java` | **`@Transactional(REQUIRES_NEW)`** 지급 쓰기. **별도 빈이어야 하는 이유는 §6-5** | 모듈 밖 참조 불가 |
| `payment/internal/TossOrderClient.java` | 토스 `get-order-status` 호출 (mTLS) | 모듈 밖 참조 불가 |
| `payment/internal/TossOrderStatus.java` | 토스 응답 8종 + `resultType` 봉투 → 우리 판정 매핑 | 모듈 밖 참조 불가 |
| `payment/internal/WebhookAuthenticator.java` | Basic Auth 검증 (U11) | 모듈 밖 참조 불가 |
| `payment/internal/SubscriptionGate.java` | `status` + `expiresAt` + STALE → `accessible` 판정(§4-3·§4-7-1) | 모듈 밖 참조 불가 |
| `payment/internal/ProductCatalogProperties.java` | `sku` 설정 바인딩 (`@ConfigurationProperties`) | 모듈 밖 참조 불가 |
| `bootstrap/package-info.java` | `@ApplicationModule(displayName="진입", allowedDependencies={"shared","auth","payment"})` | — |
| `bootstrap/BootstrapController.java` | `POST /api/v1/bootstrap` | public |
| `bootstrap/dto/BootstrapResponse.java` | `record(newUser, registeredAt, entitlement)` | public |
| `shared/security/AnonymousKeyHasher.java` | **이동** — `auth/internal` 에서 승격(§2-1 쟁점 1·§7) | public |
| `config/SecurityConfig.java` | **변경** — 공개 경로에 상품·웹훅 추가(§7) | 기존 |
| `shared/exception/ErrorCode.java` | **변경** — `PAY` 섹션 7건(§9) | 기존 |

**시그니처 수준**

```
PaymentService                                     -- payment 밖에서 부를 수 있는 전부
  ProductCatalog products()                        -- U1. 설정에서 읽는다. DB 를 보지 않는다
  GrantResult    grant(String anonKey, String orderId)   -- U2·U3·U4. ⚠️ @Transactional 없음(§6-2)
  EntitlementView entitlementOf(String anonKey)    -- U5. 읽기 전용. 토스를 부르지 않는다
  EntitlementView recheck(String anonKey, SubscriptionSnapshot fromClient)  -- §4-7-1⑥
  UsageTicketView reserve(String anonKey)          -- U6·U7. 없으면 BusinessException(PAY_001)
  void            commit(Long ticketId)            -- 작업 성공
  void            release(Long ticketId)           -- U8. 작업 실패 → 되돌린다
  void            handleWebhook(String authHeader, WebhookEvent event)  -- U9·U10·U11

GrantWriter                                        -- internal. PaymentService 와 반드시 다른 빈(§6-5)
  PaymentOrder grant(String hash, String orderId, String sku, ProductType type, LocalDateTime now)
                                                   -- @Transactional(REQUIRES_NEW)
                                                   -- 주문 원장 + (횟수권 +1 | 기간권 생성) 을 한 트랜잭션에

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
grant(anonKey, orderId):                        -- ⚠️ @Transactional 을 붙이지 않는다(§6-2)
  hash = hasher.hash(anonKey)

  1. 기존 = orderRepository.findByOrderId(orderId)          -- 자체 TX (읽기)
     기존 있음:
        기존.hash == hash → return 멱등 200 (현재 entitlement)   -- U3. 재요청은 에러가 아니다
        기존.hash != hash → throw PAY_005                        -- U4 선점

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
        grantWriter.grant(hash, orderId, sku, type, now)    -- REQUIRES_NEW. 여기서만 쓴다
     catch DuplicateKeyException:                            -- 경쟁에서 졌다(§6-3)
        경쟁자 = findByOrderId(orderId)                       -- 자체 TX → 새 스냅샷
        경쟁자.hash == hash → 멱등 200
        경쟁자.hash != hash → PAY_005

  5. return GrantResult(granted=true, type, entitlementOf(hash))   -- ⚠️ 커밋 이후에 만든다(§4-5-1ⓒ)
```

- **U14 준수**: `PAY_00x` 어느 것도 `orderId` 를 본문에 싣지 않는다. 로그도 마스킹한다(§9).
- **이 멱등 구조가 곧 U12(미결 주문 복원 지원)의 구현이다** — 복원은 별도 엔드포인트가 아니라
  `grant` 재사용이고(payment.md §6-3), 그게 가능한 근거가 재요청 200 이다.
- **2 를 1 보다 뒤에 두는 이유**: 이미 지급된 주문이면 토스를 부를 필요가 없다.
  복원 흐름(U12)은 **이미 지급된 주문을 다시 보내는 것이 정상**이라 이 경로가 자주 탄다.
  분당 3,000 QPM(미니앱 합산) 을 아끼는 실질 효과가 있다.
- ⚠️ **`PAYMENT_COMPLETED` 를 지급 대상으로 본다**(✅-1). 문서 근거가 없는 확정이므로
  **이 분기를 한 줄로 몰아 놓는다** — 뒤집힐 때 고칠 곳이 한 곳이다(§12-2).

### 5-3. `entitlementOf` — U5 구현

```
entitlementOf(anonKey):                         -- 읽기 전용. ⚠️ 토스를 부르지 않는다
  hash = hasher.hash(anonKey)
  credits = creditRepository.findByHash(hash).map(balance).orElse(0)
  sub     = subscriptionRepository.findByHash(hash)     -- 없으면 status=NONE

  stale       = gate.stale(sub, clock.now())
  accessible  = gate.accessible(sub, clock.now()) || credits > 0
  return EntitlementView(accessible, credits, stale, sub)
```

- **토스를 부르지 않는 것이 계약이다.** 부트스트랩은 부분 실패를 허용하지 않으므로(§2-2),
  여기서 외부를 부르면 **토스 장애가 진입 자체를 막는다**(payment.md §5-3).
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
- **`changeReason` 으로 분기하지 않는다.** 12종에 1:1 로 로직을 붙이면 미문서화 값이 추가될 때 깨진다.
  `changeReason` 은 **로그에만 남기고**, 반영은 `current.status`·`current.expiresAt`·`current.autoRenew`
  세 값으로 한다 — `RESTARTED` 인데 `autoRenew=false` 인 사례가 보고된 이유가 이것이다.
- **`accessGranted` 를 쓰지 않는다** — 산식이 문서에 없다(payment.md §4-3).
- ⚠️ **4 의 실패가 5 를 막지 않는다.** 재전송 정책이 문서에 없어 재시도에 기댈 수 없으므로,
  반영 실패는 **로그로 남기고 204** 를 답한다(payment.md §4-7).
- ⚠️ **`occurredAt` 은 timezone 이 없다.** 수신 즉시 KST 로 해석해 `LocalDateTime` 으로 저장한다(✅-7).

### 5-5. `recheck` — §4-7-1⑥ 구현

```
recheck(anonKey, fromClient):
  hash = hasher.hash(anonKey)
  sub  = subscriptionRepository.findByHash(hash)
  없음 → throw PAY_004                          -- 구독 이력이 없는데 재확인할 게 없다
  gate.stale(sub, now) 아님 → 현재 entitlement 반환(200)   -- 멱등. 이미 해소됐다

  applyWriter.applyFromClient(sub.id, fromClient)          -- @Transactional
     status/expiresAt/autoRenew ← fromClient
     expiresAtEstimated = false
     ⚠️ lastWebhookOccurredAt 은 건드리지 않는다             -- §4-7-1⑦
  return entitlementOf(anonKey)
```

- 🔴 **`fromClient` 는 클라가 보낸 값이다**(§4-7-1⑧ⓐ). **결제 없이 `{status:"ACTIVE"}` 만 보내도
  통과한다.** 알고 여는 구멍이며 출시 전에 닫아야 한다 → §12-3.
- **`lastWebhookOccurredAt` 을 갱신하지 않는 것이 이 설계의 급소다.** 갱신하면 뒤늦게 도착한 웹훅이
  "과거 이벤트"로 판정돼 버려지고, **클라가 보낸 값이 영구히 정본이 된다.**
  건드리지 않으면 웹훅이 recheck 결과를 정상적으로 덮는다 — **웹훅이 정본, recheck 는 임시 보정.**

### 5-6. `reserve` / `commit` / `release` — U6·U7·U8 구현

```
reserve(anonKey):                               -- @Transactional
  hash = hasher.hash(anonKey)
  sub  = findByHash(hash)

  gate.stale(sub, now)      → throw PAY_007 (403)        -- 상태 미확인. recheck 유도
  gate.accessible(sub, now) → 티켓(SUBSCRIPTION) 저장 후 반환   -- ✅-4ⓒ 기간권 우선. 차감 없음

  차감 = creditRepository.decrementIfPositive(hash)      -- 조건부 UPDATE. 0행이면 부족(§6-4)
  차감 == 0 → throw PAY_001 (403)                        -- 결제 유도
  티켓(CREDIT) 저장 후 반환

commit(ticketId):                               -- @Transactional
  RESERVED 아니면 무시(멱등)  →  status = COMMITTED

release(ticketId):                              -- @Transactional. U8
  RESERVED 아니면 무시(멱등)
  source == CREDIT → creditRepository.increment(hash)    -- 되돌린다
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
| **C2** | 같은 익명키로 **다른 두 주문**을 동시 지급 | 단건 2건 연속 구매 · 복원이 여러 건을 병렬 처리 | 잔량 +2. **행 생성 경쟁이 있다** |
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
| **바깥 트랜잭션 없음 + 쓰기만 `REQUIRES_NEW` + `DuplicateKeyException` catch** | ✅ **채택** | auth 와 같은 구조. **호출마다 새 스냅샷**이라 정합성이 격리 수준에 의존하지 않는다 |

> **auth 와 다른 점 하나**: 잡는 예외를 `DataIntegrityViolationException` 이 아니라
> **`DuplicateKeyException`(Spring 표준 변환, H2·MySQL 동일)** 으로 좁힌다.
> auth-design **§12-5 가 남긴 권고를 처음부터 반영**한 것이다 — 넓게 잡으면 NOT NULL 위반·길이 초과가
> 경쟁 처리 분기로 흘러들어 **재조회가 비고 단서 없는 500** 이 난다.
> payment 는 컬럼이 auth 보다 많아 그 위험이 더 크다.

### 6-4. 대안 비교와 채택 — C3(잔량 차감)

| 방식 | 판정 | 이유 |
|---|---|---|
| 읽고 → 빼고 → 저장 | ❌ | 함정 ⑤ — 잔량 1로 작업 2건 |
| `@Version` 낙관적 락 | △ | 동작하나 **예외 번역 + 재시도 루프**가 붙는다. 실패가 "충돌"인지 "부족"인지 구분도 안 된다 |
| `SELECT ... FOR UPDATE` | △ | 동작하나 **행 락을 트랜잭션 끝까지 잡는다.** 얻는 것에 비해 비싸다 |
| **조건부 UPDATE 한 방** | ✅ **채택** | `UPDATE ... SET balance = balance - 1 WHERE hash = ? AND balance > 0` → **영향 행 수가 곧 판정이다** |

```
decrementIfPositive(hash): int              -- @Modifying JPQL 조건부 UPDATE (구현은 코드 몫)
  balance = balance - 1  WHERE hash = ? AND balance > 0
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

**C2(잔량 행 생성 경쟁)** — 같은 익명키의 첫 두 주문이 동시에 오면 `CreditBalance` insert 가 겹친다.

```
증가(hash):
  1. incrementIfExists(hash)  -- UPDATE ... SET balance = balance + 1 WHERE hash = ?
     1행 → 끝
  2. 0행 → insert(hash, 1)
     DuplicateKeyException → 경쟁자가 방금 만들었다 → 1 을 다시 시도 (반드시 1행)
```

- **재시도는 정확히 1회다.** 행은 한 번만 생기므로 두 번째 `incrementIfExists` 는 반드시 성공한다.
  루프를 돌지 않는다 — 무한 루프 가능성을 원천 제거한다.

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
- ⚠️ **이 근거는 `DuplicateKeyException` 일 때만 참이다.** §6-3 에서 예외를 좁힌 이유가 이것이다.
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

---

## 7. 기존 코드 리팩터링

**전수 목록.**

| 파일 | 변경 | 영향 |
|---|---|---|
| [AnonymousKeyHasher.java](../../src/main/java/kang20/ytcreator/auth/internal/AnonymousKeyHasher.java) | **`shared/security/` 로 이동** (§2-1 쟁점 1) | 🔴 **auth 의 import 가 바뀐다.** 로직은 한 글자도 바뀌지 않으므로 `AnonymousKeyHasherTest` 도 패키지만 이동 |
| [AuthService.java](../../src/main/java/kang20/ytcreator/auth/AuthService.java) | import 경로만 변경 | 동작 무변경. **auth-design §4 표의 위치 표기도 함께 고친다** |
| [SecurityConfig.java](../../src/main/java/kang20/ytcreator/config/SecurityConfig.java) | `PUBLIC_PATHS` 에 **`/api/v1/payments/products`** 와 **`/api/v1/webhooks/toss/**`** 추가 | ⚠️ **웹훅을 빠뜨리면 토스 웹훅이 401 로 튕겨 U9 가 통째로 죽는다**(payment.md §10-8ⓐ). 웹훅은 permitAll 이지만 **모듈이 Basic Auth 로 다시 막는다**(§2-1 쟁점 3) |
| [ErrorCode.java](../../src/main/java/kang20/ytcreator/shared/exception/ErrorCode.java) | `PAY` 섹션 7건 추가 (§9) | 기존 코드 무변경 |
| [GlobalExceptionHandler.java](../../src/main/java/kang20/ytcreator/shared/exception/GlobalExceptionHandler.java) | **변경 없음이 정책** | ✅ **502 핸들러 신설이 불필요하다.** `handleBusiness` 가 `code.getStatus()` 를 그대로 쓰므로 `ErrorCode` 에 `BAD_GATEWAY` 를 넣으면 자동 처리된다 — **payment.md §7 의 "신설 필요" 우려는 기우다** |
| [ControllerTest.java](../../src/test/java/kang20/ytcreator/base/ControllerTest.java) | **변경 없음이 정책** | `@Import(SecurityConfig.class)` 라 슬라이스 테스트가 default-deny 를 받는다. **payment 컨트롤러 테스트는 익명키 헤더를 붙여야 200 이 난다** — auth-design §7 이 남긴 선례를 그대로 따른다 |
| [common.adoc](../../src/docs/asciidoc/common.adoc) | 시간대 절 확인 | `expiresAt` 이 **사용자에게 보이는 첫 시각**이다 → §12-4 |
| [index.adoc](../../src/docs/asciidoc/index.adoc) | `payment.adoc`·`bootstrap.adoc` include 추가 | **이번엔 실제로 스니펫이 생긴다** — auth 와 달리 컨트롤러가 있다 |
| [toss-integration.md](../rule/toss-integration.md) | 3건 정정 (payment.md §10-6) | ⓐ 서버용 구독 조회 API 부재 명시 ⓑ *"hash 인증으로 인앱결제도 호출"* → **IAP 는 mTLS + `orderId` 뿐** ⓒ 단건 흐름 추가 |
| [CLAUDE.md](../../../CLAUDE.md) | *"인앱결제가 익명키만으로 지원된다"* 정정 (payment.md §10-7) | **IAP 는 `x-anon-key` 를 쓰지 않는다** |
| [auth.md](./auth.md) | **v3 로 올린다** — §4-2 공개 목록에 웹훅·상품 경로, §5-2 부트스트랩 스키마 확대 | 🔴 **v2 확정본이라 조용히 못 고친다** → §12-1 |
| [auth-design.md](./auth-design.md) | §4 표의 `AnonymousKeyHasher` 위치, §12-1(`bootstrap` 미룸) 해소 표기 | 설계 정본이므로 역반영 필요 |

- **삭제된 요구가 남긴 코드는 없다.** payment 는 신규 도메인이다.
- ⚠️ **auth 의 테스트가 깨지는 유일한 지점은 해셔 이동**이다. 이동 커밋에서 `./gradlew test` 를
  통과시키고 넘어간다 — payment 구현과 섞지 않는다(§11).

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
  `AnonymousKeyFormat.mask()` 와 같은 방식으로 앞 4자만 남긴다.
- 네이밍은 [error-handling.md](../rule/error-handling.md).

---

## 10. 테스트 계획

| 테스트 | 종류 | 핵심 케이스 |
|---|---|---|
| `PaymentGrantTest` | `@ApplicationModuleTest` | 최초 지급(단건 +1 / 구독 생성) · **재요청 200 + 잔량 불변**(U3·**U12** — 복원 재전송이 이 경로다) · 남의 주문 `PAY_005`(U4) · 토스 status 8종 → 응답 매핑 전수 · `sku` 가 카탈로그에 없으면 `PAY_004` |
| `PaymentGrantConcurrencyTest` | **비TX 멀티스레드** | 🔴 **C1** — 같은 `orderId` 동시 N회 → **주문 1행·잔량 정확히 +1**, 예외·500 없음. **`grant` 를 트랜잭션 밖에서 호출해야 실제 경쟁이 재현된다** / **C2** — 같은 익명키·다른 주문 N건 동시 → 잔량 정확히 +N (행 생성 경쟁, §6-4) |
| `CreditConsumeConcurrencyTest` | **비TX 멀티스레드** | 🔴 **C3** — 잔량 1에 동시 N건 `reserve` → **정확히 1건만 성공, 나머지 `PAY_001`, 잔량 0. 음수 없음** / **C4** — 같은 티켓 동시 `release` N회 → `+1` 정확히 1회 |
| `PaymentTransactionBoundaryTest` | 단위(리플렉션) | **함정 ③·④ 회귀 방지** — `PaymentService.grant` 에 `@Transactional` 이 **없음**, `GrantWriter.grant` 가 **`REQUIRES_NEW`**, `BootstrapService`(있다면)에 `@Transactional` 없음을 단언. **사람이 무심코 붙이는 순간 실패한다** |
| `SubscriptionGateTest` | 단위 | §4-3 개폐표 6종 전수 · **유예 1일 경계값**(`expiresAt`, `+1일`, `+1일 1초`) · STALE 판정(추정값 vs 정본) · 구독 이력 없음 |
| `PaymentWebhookTest` | `@ApplicationModuleTest` | 등록 검증 이벤트 → 204(U10) · Basic 불일치 → 401 **+ 상태 무변경**(U11) · 모르는 `orderId` → 무시 + 구독 미생성(✅-5) · `occurredAt` 역전 → 무시(C5) · `previous` 불일치 → **WARN 로그 + 반영은 진행** · `REVOKED` → 즉시 회수(**U13**) · `AUTO_RENEW_DISABLED` → **만료일까지 유지** · `RESTARTED` + `autoRenew=false` → **`current` 를 따른다** |
| `SubscriptionRecheckTest` | `@ApplicationModuleTest` | STALE → recheck → 게이트 재개방 · **`lastWebhookOccurredAt` 이 갱신되지 않음**(§4-7-1⑦) · **recheck 이후 도착한 과거 웹훅이 정상 반영됨**(위계 검증) · STALE 아닐 때 멱등 200 |
| `TossOrderClientTest` | 단위(MockRestServiceServer) | `resultType` 7종 → 성공/실패 분류 · **비즈니스 오류가 HTTP 200 으로 오는 경우** · `success.sku` 부재 방어 · 타임아웃 → `PAY_006` |
| `PaymentControllerTest` | `@WebMvcTest` + REST Docs | §8 스니펫 전부. **응답·에러 본문에 `orderId` 가 없음**(U14) |
| `PaymentWebhookControllerTest` | `@WebMvcTest` + REST Docs | 204 · 401 · **반영 실패해도 204** |
| `BootstrapControllerTest` | `@WebMvcTest` + REST Docs | 성공 + **게이트 401 두 종류**(auth-design §8 숙제 해소) |
| `PaymentModuleBoundaryTest` | 구조 | `verify()` 가 못 잡는 불변식: ⓐ `payment.allowedDependencies` 가 `shared` 뿐 — **`auth` 를 적어 넣으면 `verify()` 는 오히려 정상으로 본다**(auth-design §10 선례) ⓑ **수동 DDL ↔ 엔티티 매핑 대조**(§3-1) ⓒ payment 가 `subtitle`·`auth` 를 참조하지 않음 |
| `ModularityTest` | 구조 (기존) | 순환 없음 · `bootstrap` → `auth`·`payment` 단방향 |

**어떤 테스트가 어떤 라인을 덮는가**

| 산출물 | 덮는 테스트 | 비고 |
|---|---|---|
| `payment/PaymentService.java` | `PaymentGrantTest` + 동시성 2본 + `SubscriptionRecheckTest` | **catch 분기는 동시성 테스트로만 도달한다** — 목으로 예외를 흉내 내면 §6-5 트랜잭션 경계가 검증되지 않는다 |
| `payment/internal/GrantWriter.java` | `PaymentGrantTest` + `PaymentGrantConcurrencyTest` | 정상 커밋 + 경쟁 시 롤백 경계 |
| `payment/internal/TossOrderClient.java` | `TossOrderClientTest` | mTLS 조립은 `enabled=false` 로 우회. **조립 자체는 단위 테스트 대상이 아니다** |
| `payment/internal/TossOrderStatus.java` | `PaymentGrantTest` + `TossOrderClientTest` | status 8종 × `resultType` 7종 매핑 전수 |
| `payment/internal/SubscriptionGate.java` | `SubscriptionGateTest` | 경계값 |
| `payment/internal/WebhookAuthenticator.java` | `PaymentWebhookTest` | 일치/불일치/헤더 없음 |
| `payment/internal/*Repository.java` | 해당 서비스 테스트 | 인터페이스 + `@Modifying` 쿼리는 **동시성 테스트가 실제로 덮는다** |
| 엔티티 4개 | `PaymentGrantTest` | 생성자·게터·상태 전이 |
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

- [ ] `refactor(auth)`: `AnonymousKeyHasher` → `shared/security` 이동 + auth import·테스트 패키지 정리 (§7).
      **이 커밋 단독으로 `./gradlew test` 그린을 확인하고 넘어간다**
- [ ] `chore(db)`: `backend/deploy/sql/payment-v1.sql` — 테이블 4개 + 인덱스 (§3-1)
- [ ] `feat(payment)`: 모듈 골격 + 엔티티·리포지토리 + `GrantWriter` + `SubscriptionGate` (§4 매핑대로)
- [ ] `feat(payment)`: `TossOrderClient` (mTLS, `enabled=false` 게이트) + `TossOrderStatus` 매핑 (§5-2)
- [ ] `feat(payment)`: `PaymentService` — grant·entitlement·reserve/commit/release (§5-2·5-3·5-6)
- [ ] `feat(payment)`: 웹훅 수신 + `WebhookAuthenticator` + recheck (§5-4·5-5)
- [ ] `feat(payment)`: `PaymentController`·`PaymentWebhookController` + `ErrorCode` PAY 7건 (§9)
- [ ] `feat(bootstrap)`: `bootstrap` 모듈 + 컨트롤러 (§2-2)
- [ ] `feat(payment)`: `SecurityConfig` 공개 경로 2건 추가 (§7)
- [ ] `test(payment)`: §10 전 항목 — **동시성 3본 포함**
- [ ] `docs(api)`: `payment.adoc`·`bootstrap.adoc` 신설 + `index.adoc` include (§8)
      🔴 **§8-1 의 K1~K20 이 본문에 전부 들어갔는지 표로 대조한다.** 스니펫이 없는 두 절
      (§미결 주문 복원 · §이용 게이트)도 반드시 쓴다 — **빠지면 프론트에게는 없는 계약이다**
- [ ] `docs(rule)`: `toss-integration.md` 3건 · `CLAUDE.md` 1건 정정 (§7)
- [ ] `docs(auth)`: `auth.md` **v3** + `auth-design.md` 역반영 (§7·§12-1)
- [ ] `./gradlew test --tests "*ModularityTest"` — 모듈 경계 통과
- [ ] **변경 파일 라인 커버리지 100% 확인** → `/code-review`
- [ ] `/docs-sync` — **이번엔 스니펫이 실제로 생긴다**(auth 와 다르다)

---

## 12. 결정 필요 (Open Questions)

> 비즈니스 결정은 [payment.md](./payment.md) §9-1 에서 **17건 전부 확정**됐다. 여기는 **설계 쟁점**만 남긴다.

### 12-1. 🔴 `auth.md` 를 v3 로 올려야 한다 — 이 설계가 확정본을 건드린다

`bootstrap` 응답에 이용권을 담는 순간 **auth.md §5-2 의 확정 계약이 바뀐다**(payment.md ✅-15ⓐ).
auth.md 는 **v2 확정본**이라 조용히 고칠 수 없다([versioning.md](../../../.claude/skills/usecase/references/versioning.md)).

| 고칠 곳 | 내용 |
|---|---|
| auth.md §4-2 | 공개 목록에 **웹훅 경로 + 상품 조회 경로** 추가 |
| auth.md §5-2 | 부트스트랩 응답에 `entitlement`(= payment.md §5-3 스키마) 추가 |
| auth.md 전반 | 도메인 이름 `subscription` → **`payment`** (payment.md §10-8ⓒ — grep 으로 훑을 것) |

**권고**: `feat(bootstrap)` 커밋 **전에** auth.md v3 를 먼저 올린다. 순서가 뒤바뀌면
**구현이 확정 문서를 앞지른 상태**가 되고, 그건 이 프로젝트가 금지한 흐름이다.

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

**이 설계서에서 임의로 정하지 않는다.** ✅-4 처럼 **사용자 판단**이 필요한 축이고,
`/implement` 호출 시 결정하면 §5-5 한 곳만 바뀐다.

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
