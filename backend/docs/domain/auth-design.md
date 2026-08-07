# Auth 도메인 설계

> 비즈니스 요구사항(유스케이스 명세): [auth.md](./auth.md) — **요구·정책·API 계약의 정본**. 이 문서는 소프트웨어 설계만 다룬다.
> **대응 유스케이스 버전: v1**
> 서버 계약(정본): `docs/server/api-spec.md` — **아직 존재하지 않는다.** 이 설계가 첫 입주 대상이다(auth.md §10-7)
> 구조 규칙: [architecture.md](../rule/architecture.md) · 에러: [error-handling.md](../rule/error-handling.md) · 테스트: [testing.md](../rule/testing.md) · REST Docs: [rest-docs.md](../rule/rest-docs.md)
> 상태: 설계 확정 대기 — 승인 후 구현 착수

> ⚠️ **첫 도메인이다.** `kang20.ytcreator` 아래에 도메인 모듈이 하나도 없다(`shared`·`config` 만 존재).
> 인용할 선례가 없으므로 **이 설계가 이후 모든 도메인의 선례**가 된다.

## 1. 목적과 범위

익명키 하나로 사용자를 등록·식별하고, 인증이 필요한 엔드포인트를 게이트로 막는다.
**요구는 [auth.md](./auth.md) §1~§7 을 따르고 여기 중복 기술하지 않는다.**

**이번 구현 산출물**

- 신규 모듈 `auth` — 사용자 등록·식별(U2·U4)
- `shared/security` 확장 — 형식 검증(U5)·게이트 진입점(U3)·마스킹(U6)
- `config/SecurityConfig` 변경 — `permitAll` → 기본 인증 필요(auth.md §4-2 확정)
- 신규 테이블 1개 + 수동 DDL 1본

**범위 외**

- **집계 모듈 `bootstrap` 의 구현** — 설계는 §2-2 에서 하되 구현은 미룬다(사유·근거 §12-1)
- `subscription` 도메인 일체 — auth.md §2 에서 범위 외로 확정
- rate limit·도용 탐지·익명키 이관 — auth.md §8 백로그

---

## 2. 도메인 경계

**책임** — `auth` 는 **“이 익명키에 해당하는 사용자가 존재한다”는 사실**에 단일 권위를 갖는다.
그 이상은 알지 않는다: 구독도, 결제도, 작업도 모른다.

**다른 모듈과의 관계**

| 상대 모듈 | 관계 | 경계 처리 |
|---|---|---|
| `shared` | 사용 (OPEN 모듈) | 자유 참조. `BaseTimeEntity`·`ErrorCode`·`BusinessException` |
| `config` | 역방향 참조당함 | `config` 가 `shared/security` 의 필터·진입점을 조립한다. **`config` → `auth` 의존은 만들지 않는다**(§2-1 쟁점 3) |
| `bootstrap` (미구현) | **참조당함** | `bootstrap` → `auth` 단방향. `AuthService` 를 직접 호출한다 |
| `subscription` (미존재) | **없음** | ⚠️ **auth 는 subscription 을 영원히 참조하지 않는다** — auth.md §4-7 확정 |

- **이벤트를 발행하지 않는다.** [architecture.md](../rule/architecture.md) 는 모듈 간 통신에 이벤트를
  우선하지만, MVP 에 `UserRegistered` 를 구독할 모듈이 **하나도 없다.** 소비자 없는 이벤트는
  아웃박스 테이블만 채우고 검증할 대상도 없다 — 소비자가 생길 때 만든다.
- `bootstrap` → `auth` 는 **직접 호출**이다. 진입 응답을 동기로 조립해야 하므로 이벤트로 대체할 수
  없다. `bootstrap/package-info.java` 의 `allowedDependencies` 에 명시해 의도를 고정한다(§4).

### 2-1. ⚠️ 설계 쟁점

#### 쟁점 1 — 게이트가 내는 401 은 `GlobalExceptionHandler` 에 도달하지 않는다

**문제.** auth.md §4-2 는 401 응답이 `AUTH_001`/`AUTH_002` 코드를 담은 공통 에러 본문이어야 한다고
계약했다. 그런데 `SecurityConfig` 를 기본 인증 필요로 바꾸면, 미인증 요청은 **보안 필터 체인 안에서**
끝나고 DispatcherServlet 에 닿지 않는다. `@RestControllerAdvice` 인
[GlobalExceptionHandler](../../src/main/java/kang20/ytcreator/shared/exception/GlobalExceptionHandler.java)
는 호출되지 않는다 — [error-handling.md](../rule/error-handling.md) 가 이미 명시한 사실이다.

→ 그대로 두면 Spring Security 기본 401(본문 없음)이 나가고 **프론트의 `AUTH_001`/`AUTH_002` 분기가
전부 죽는다**(auth.md §6-1·§6-2).

**해결.** `AuthenticationEntryPoint` 구현체를 두고 `ErrorResponse` 를 직접 직렬화한다.

```
요청 ──▶ AnonymousKeyFilter ──▶ 인가 규칙(SecurityConfig)
             │                        │ 미인증
             │ 거부 사유를              ▼
             └─ request attribute ─▶ AnonymousKeyEntryPoint
                로 남긴다                 └─ attribute 를 읽어 AUTH_001 / AUTH_002 를 가르고
                                            ErrorResponse{code,message} 를 401 로 직접 쓴다
```

**규칙 정합성** — 본문 형식은 `ErrorResponse` 그대로라
[error-handling.md](../rule/error-handling.md) 의 공통 규격과 어긋나지 않는다. 새 에러 코드도 없다.

#### 쟁점 2 — 형식 검증을 필터가 “거부”하면 계약이 깨진다

**문제.** U5(형식 검증)를 필터에서 바로 401 로 끊고 싶어지지만, auth.md §4-2 는
**공개 엔드포인트에서는 형식이 틀린 익명키도 무시하고 200** 을 주기로 계약했다.
필터가 끊으면 공개 엔드포인트까지 막혀 계약 위반이다.

**해결.** **필터는 어떤 경우에도 거부하지 않는다.** 현재
[AnonymousKeyFilter](../../src/main/java/kang20/ytcreator/shared/security/AnonymousKeyFilter.java)
의 설계 원칙(*“여기서 401 을 내지 않는다”*)을 그대로 유지·확장한다.

| 헤더 상태 | 필터가 하는 일 | 판정 주체 |
|---|---|---|
| 없음 | 인증 미설정 + attribute `MISSING` | 인가 규칙 |
| 형식 위반 | 인증 미설정 + attribute `MALFORMED` | 인가 규칙 |
| 정상 | `AnonymousAuthentication` 설정 | — |

→ 공개 경로면 인가 규칙이 통과시키므로 attribute 는 쓰이지 않고 버려진다. 인증 필요 경로면
진입점이 attribute 를 읽어 코드를 가른다. **한 필터가 두 계약을 모두 만족한다.**

#### 쟁점 3 — 순환 의존을 만들 수 있는 두 지점

`ApplicationModules.verify()` 는 순환을 잡는다([ModularityTest](../../src/test/java/kang20/ytcreator/ModularityTest.java)).
아래 둘을 설계 시점에 차단한다.

| 위험 | 왜 생기나 | 차단 |
|---|---|---|
| `auth ↔ subscription` | 진입 응답을 auth 가 조립하려 하면 auth → subscription 이 생기고, subscription 이 사용자를 참조하면 역방향이 닫힌다 | **`bootstrap` 이 양쪽을 한 방향으로 참조**(auth.md §4-7 확정). auth 는 subscription 을 모른다 |
| `config → auth` | 게이트 부품을 `auth` 모듈에 두면 `SecurityConfig` 가 도메인 모듈을 참조하게 된다 | 게이트 부품(필터·진입점·마스킹)을 **`shared/security` 에 둔다**. 게이트는 전 도메인 공통이지 auth 의 소유물이 아니다 |

> 결과적으로 **`auth` 모듈의 `allowedDependencies` 는 `shared` 하나뿐**이다. 가장 밑에 있어야 할
> 모듈이 실제로 가장 밑에 놓인다.

### 2-2. 집계 모듈 `bootstrap` 의 설계 (구현은 §12-1 참조)

auth.md §4-7 확정 사항의 구조적 형태만 미리 고정한다.

```
POST /api/v1/bootstrap
   └─▶ bootstrap 모듈 (allowedDependencies = { shared, auth, subscription })
          ├─▶ AuthService.register(anonymousKey)        → newUser, registeredAt
          └─▶ SubscriptionService.statusOf(anonymousKey) → 구독 상태
```

- **자기 저장소를 갖지 않는다.** 엔티티·리포지토리가 생기면 그건 집계가 아니라 새 도메인이므로
  별도 유스케이스 문서가 필요하다(auth.md §4-7).
- `auth` 와 `subscription` 은 `bootstrap` 을 **모른다.**
- ⚠️ `subscription` 이 사용자를 **익명키로 키를 잡을지, `auth` 의 사용자 식별자를 참조할지**는
  `subscription` 도메인의 설계 결정이다. 어느 쪽이든 순환은 생기지 않는다(auth 가 subscription 을
  참조하지 않으므로). **다만 이 불변식은 깨지면 안 된다** — §10 에 구조 테스트로 고정한다.

---

## 3. 엔티티

```
User                              -- auth 모듈. 익명키당 정확히 하나(멱등, U2)
  id             Long             -- surrogate PK, IDENTITY
  anonymousKey   String(255)      -- 토스 익명키 hash 원문
  createdAt      LocalDateTime    -- BaseTimeEntity. 응답의 registeredAt 이 이 값이다
  updatedAt      LocalDateTime    -- BaseTimeEntity
  UNIQUE (anonymous_key)          -- 멱등의 근거. 동시 등록 경쟁을 DB 가 최종 판정한다(§6)
```

- [BaseTimeEntity](../../src/main/java/kang20/ytcreator/shared/domain/BaseTimeEntity.java) **상속**한다 —
  `registeredAt` 을 위한 별도 컬럼을 만들지 않는다. 등록 시각 = 행 생성 시각이라 의미가 정확히 일치하고,
  컬럼이 둘이면 어긋날 여지만 생긴다.
- **surrogate PK 를 쓴다.** `anonymousKey` 를 PK 로 삼으면 (a) 다른 모듈이 FK 로 익명키 원문을 들고
  다니게 되어 U6(비노출) 이 넓어지고, (b) 미니앱 재출시로 익명키가 바뀔 때(auth.md §4-6) PK 를
  갈아야 한다. 식별자와 PK 를 분리해 둔다.
- ⚠️ **`anonymousKey` 의 길이 255 는 잠정값이다** — auth.md 🔶-3(형식 규격 보류)이 이 컬럼에 직접
  닿는다. 근거: MySQL `utf8mb4` 유니크 인덱스 상한(3072 bytes ≈ 768자) 안이고 Hibernate 기본값이라
  안전 측이다. 스파이크 6 에서 실제 hash 길이가 나오면 조정한다(§12-2).

### 3-1. 수동 DDL — **필요하다**

운영은 `ddl-auto: ${JPA_DDL_AUTO:validate}`([application-prod.yml](../../src/main/resources/application-prod.yml))이라
**스키마가 배포로 만들어지지 않는다.** 신규 테이블이므로 배포 **전에** 수동 적용해야 한다.

| 산출물 | 내용 | 적용 시점 |
|---|---|---|
| `backend/deploy/sql/event-publication-v1.sql` | 모듈 이벤트 아웃박스 `EVENT_PUBLICATION` 테이블 | **앱 배포보다 먼저** |
| `backend/deploy/sql/auth-v1.sql` | `users` 테이블 + `anonymous_key` UNIQUE 인덱스 | **앱 배포보다 먼저** |

- `backend/deploy/sql/` 디렉터리가 **아직 없다.** 이 도메인이 처음 만든다 — 이후 도메인의 선례가 된다.
- 테스트는 `ddl-auto: create-drop`([testing.md](../rule/testing.md))이라 자동 생성된다.

⚠️ **`EVENT_PUBLICATION` 은 auth 의 테이블이 아니지만 이번 배포의 선행 조건이다.**

- `spring-modulith-starter-jpa`([build.gradle.kts](../../build.gradle.kts))가 이벤트 발행 레지스트리를
  **JPA 엔티티로 등록**한다. `ddl-auto: validate` 는 퍼시스턴스 유닛에 등록된 **모든** 엔티티의 스키마를
  검증하므로, **어떤 모듈도 이벤트를 발행하지 않아도** 테이블이 없으면 기동이 실패할 수 있다.
- [application.yml](../../src/main/resources/application.yml) 주석도 조건을 “이벤트를 쓰면”이 아니라
  **“첫 배포 전에”** 로 걸어 두었다. **auth 가 그 첫 배포다.**
- 따라서 “auth 는 이벤트를 안 쓰니 무관하다”는 판단을 하지 않는다. **보수적으로 함께 적용**한다 —
  불필요했다면 빈 테이블 하나가 남을 뿐이지만, 빠뜨리면 **운영 기동 실패**다. 비용이 비대칭이다.
- 구현 시 실제 검증 동작을 한 번 확인하고 결과를 이 문서에 역반영한다(§11).

---

## 4. 모듈 매핑 (Spring Modulith)

| 위치 | 산출물 | 공개 여부 |
|---|---|---|
| `auth/package-info.java` | `@ApplicationModule(displayName="인증", allowedDependencies={"shared"})` | — |
| `auth/AuthService.java` | 모듈 공개 API. `Registration register(String anonymousKey)` | **public (모듈 밖 유일 진입)** |
| `auth/dto/Registration.java` | `record Registration(boolean newUser, LocalDateTime registeredAt)` | public |
| `auth/internal/User.java` | 엔티티 | 모듈 밖 참조 불가 |
| `auth/internal/UserRepository.java` | `findByAnonymousKey` / `save` | 모듈 밖 참조 불가 |
| `auth/internal/UserWriter.java` | **신규 빈** — `@Transactional(REQUIRES_NEW) User insert(String)`. **별도 빈이어야 하는 이유는 §6-4** | 모듈 밖 참조 불가 |
| `shared/security/AnonymousKeyFilter.java` | **변경** — 형식 검증 + 거부 사유 attribute (U1·U5) | 기존 public |
| `shared/security/AnonymousKeyEntryPoint.java` | **신규** — 401 본문 직접 작성 (U3) | public |
| `shared/security/AnonymousKeyFormat.java` | **신규** — 형식 규칙 + `mask()` (U5·U6) | public |
| `shared/security/AnonymousAuthentication.java` | **변경** — 주석만 갱신 | 기존 public |
| `config/SecurityConfig.java` | **변경** — 기본 인증 필요 + 공개 경로 열거 + 진입점 등록 (U3) | 기존 |

**시그니처 수준**

```
AuthService
  Registration register(String anonymousKey)      -- ⚠️ @Transactional 없음(의도적). 멱등(U2). §5-1·§6-4
                                                     경쟁 시 UNIQUE 위반을 흡수해 기존 사용자를 돌려준다
                                                     (트랜잭션 경계는 §6-4 판정 매트릭스가 정본)
    └─ UserWriter 를 주입받아 호출한다              -- 자기 호출이면 REQUIRES_NEW 가 안 걸린다(§6-4)

UserWriter                                        -- internal. AuthService 와 반드시 다른 빈
  User insert(String anonymousKey)                -- @Transactional(REQUIRES_NEW)

AnonymousKeyFormat                                -- 상수 + 순수 함수. 상태 없음
  boolean isValid(String raw)                     -- U5. 규칙 값은 §12-2 로 잠정
  String  mask(String raw)                        -- U6. 앞 4자 + "***" (auth.md §4-5 확정)

AnonymousKeyEntryPoint implements AuthenticationEntryPoint
  void commence(req, res, authException)          -- attribute → AUTH_001 | AUTH_002 → ErrorResponse 401
```

- **`AuthService` 가 모듈 루트의 유일한 public 타입**이다. `User`·`UserRepository`·`UserWriter` 는
  `internal/` 이라 Modulith 가 외부 참조를 차단한다([architecture.md](../rule/architecture.md)).
- `Registration` 에 **사용자 식별자(id)를 담지 않는다.** `bootstrap` 이 필요로 하지 않고,
  담는 순간 다른 모듈이 auth 의 PK 를 들고 다니게 된다.
- 게이트 부품이 `auth` 가 아니라 `shared/security` 인 이유는 §2-1 쟁점 3.

---

## 5. 비즈니스 로직 (서비스 흐름)

### 5-1. `AuthService.register` — U2·U4 구현

```
register(anonymousKey):                      -- ⚠️ 트랜잭션 없음 (의도적 — §6-4)
  1. findByAnonymousKey(anonymousKey)                                 -- 자체 트랜잭션(읽기)
       존재 → return Registration(newUser=false, 기존.createdAt)      -- 멱등(U2)
  2. 없음 → userWriter.insert(anonymousKey)                           -- 별도 쓰기 트랜잭션
       성공          → return Registration(newUser=true, 신규.createdAt)
       UNIQUE 위반   → §6 경쟁 처리로 위임 (예외를 밖으로 흘리지 않는다)
```

- **형식 검증은 여기서 하지 않는다.** 요청이 여기 도달했다는 것은 이미 필터·게이트를 통과했다는
  뜻이다(U5 는 `shared/security` 소관, §4). 서비스가 다시 검사하면 책임이 두 곳으로 갈린다.
- **`register` 에 `@Transactional` 을 붙이지 않는다.** 붙이면 오히려 깨진다 — 근거는 §6-4.
- **롤백 의미**: 쓰기는 단일 행 삽입 하나뿐이라 부분 성공이 없다. 감쌀 원자성이 애초에 없다.

### 5-2. 게이트 판정 — U1·U3·U5 구현

```
AnonymousKeyFilter.doFilterInternal:          -- 절대 거부하지 않는다(§2-1 쟁점 2)
  raw = header("X-Anonymous-Key")
  공백/없음        → attribute(DENIAL) = MISSING   → chain 계속
  !Format.isValid  → attribute(DENIAL) = MALFORMED → chain 계속
  정상             → SecurityContext 에 AnonymousAuthentication 설정 → chain 계속

SecurityConfig 인가 규칙:
  공개 경로(열거)      → permitAll
  그 외               → authenticated

AnonymousKeyEntryPoint.commence:              -- 인가 규칙이 막았을 때만 호출된다
  attribute == MALFORMED → AUTH_002
  그 외(MISSING 포함)     → AUTH_001
  → 401 + ErrorResponse{code,message} 직접 write
```

- **U6 준수**: 진입점·필터 어디서도 익명키 원문을 응답이나 로그에 넣지 않는다.
  진단 로그가 필요하면 `AnonymousKeyFormat.mask()` 를 거친다.
- 공개 경로 목록의 **값**은 auth.md §4-2 가 정본이다(헬스체크·솔루션 목록/상세·부트스트랩).
  솔루션·부트스트랩 엔드포인트는 아직 없으므로 **이번 구현에서는 헬스체크만 열거**되고,
  나머지는 해당 도메인이 생길 때 추가된다(§7).

---

## 6. 동시성 제어

### 6-1. 불변식과 경쟁 시나리오

**불변식**: 같은 익명키에 대응하는 `User` 는 **정확히 하나**다(auth.md U2 멱등).

| # | 시나리오 | 발생 경로 | 요구 결과 |
|---|---|---|---|
| C1 | 처음 보는 익명키로 **동시 2회** 등록 | 프론트가 진입 시 부트스트랩을 중복 호출(리트라이·더블탭·앱 복귀 중복) | 사용자 1명. 한쪽 `newUser=true`, 다른 쪽 `false`. **에러 응답 금지** |
| C2 | 기존 익명키로 동시 다발 조회 | 정상 재방문 | 읽기만이라 경쟁 없음 |

- C1 은 **실제로 일어난다.** 부트스트랩은 진입 직후 호출이라 중복 호출 여지가 가장 큰 지점이다.

### 6-2. 함정 분석 — 왜 단순한 방법이 안 되는가

**함정 ①: "조회 후 없으면 삽입" 만으로는 못 막는다.**
두 트랜잭션이 같은 순간 `findByAnonymousKey` 에서 빈 결과를 보고 둘 다 `save` 한다.
UNIQUE 제약이 한쪽을 죽이고, 그 사용자는 **정상 진입인데 500 을 받는다.**

**함정 ②: `DataIntegrityViolationException` 을 같은 트랜잭션 안에서 잡으면 못 살린다.**
JPA 는 제약 위반이 나면 그 트랜잭션을 **rollback-only** 로 표시한다. 잡아서 재조회해도
커밋 시점에 `UnexpectedRollbackException` 이 터진다. **catch 만으로는 해결되지 않는다** —
경계를 나누지 않으면 실패한다.

**함정 ③: 락을 먼저 떠올리면 과하다.**
존재하지 않는 행에는 비관적 락을 걸 대상이 없다. 테이블 락이나 별도 잠금 테이블은
진입 경로 전체를 직렬화한다 — 검수 기준(2초)과 정면 충돌한다.

**함정 ④: 삽입만 분리하고 바깥을 트랜잭션으로 감싸면, 이번엔 재조회가 거짓말한다.**
함정 ②를 피하려고 삽입만 별도 트랜잭션으로 빼도, **바깥을 `@Transactional` 로 감싸면 새 문제가 생긴다.**
MySQL InnoDB 기본 격리 수준 `REPEATABLE READ` 는 트랜잭션의 **첫 읽기 시점에 스냅샷을 고정**한다.

```
바깥 TX:  findByAnonymousKey → 없음    ← 여기서 스냅샷 고정 ("없음"으로)
          insert (별도 TX)   → UNIQUE 위반
          findByAnonymousKey → 여전히 "없음"    ← 경쟁자가 커밋했어도 스냅샷 밖이다
```

→ 재조회가 빈 결과를 돌려주고 거기서 터진다.
⚠️ **더 나쁜 것은 이 결함이 테스트에서 안 잡힌다는 점이다.** H2 기본 격리는 `READ COMMITTED` 라
매번 새로 읽는다. 테스트 프로파일의 `MODE=MYSQL`([testing.md](../rule/testing.md))은 **문법 호환 모드일
뿐 격리 수준을 바꾸지 않는다.** 즉 **H2 에서는 통과하고 MySQL 에서 터진다** — 가장 나쁜 형태다.

### 6-3. 대안 비교와 채택

| 방식 | 판정 | 이유 |
|---|---|---|
| 조회 → 없으면 삽입 (경쟁 무시) | ❌ | 함정 ① — 정상 사용자가 500 을 받는다 |
| 같은 TX 안에서 예외 catch | ❌ | 함정 ② — rollback-only 라 커밋이 터진다 |
| 비관적 락 / 잠금 테이블 | ❌ | 함정 ③ — 진입 경로 직렬화. 얻는 것보다 잃는 게 크다 |
| DB `INSERT ... ON DUPLICATE KEY` (네이티브) | △ | 동작은 하나 **H2(MySQL 모드) 와 MySQL 문법이 갈려** 테스트 환경에서 검증이 안 된다([testing.md](../rule/testing.md)) |
| 바깥 `@Transactional` + 삽입만 `REQUIRES_NEW` | ❌ | 함정 ④ — 재조회가 바깥 스냅샷에 갇힌다. **H2 는 통과하고 MySQL 만 터지는** 형태라 더 위험하다 |
| 바깥 `@Transactional(READ_COMMITTED)` 로 고정 | △ | 동작하나 **정합성이 격리 수준이라는 숨은 지식에 매달린다.** 누가 어노테이션을 정리하다 지우면 조용히 깨진다 |
| **바깥 트랜잭션 없음 + 삽입만 `REQUIRES_NEW`** | ✅ **채택 (2026-08-07 사용자 결정)** | 호출마다 새 트랜잭션 → **새 스냅샷**. 정합성이 **격리 수준에 아예 의존하지 않게** 된다 |

### 6-4. 채택안 상세

```
AuthService.register(anonymousKey):           -- ⚠️ @Transactional 을 붙이지 않는다
  기존 = userRepository.findByAnonymousKey            -- 자체 트랜잭션 ①
  기존 있음 → Registration(false, 기존.createdAt)

  try:
     신규 = userWriter.insert(anonymousKey)            -- 다른 빈 → REQUIRES_NEW 가 실제로 걸린다 ②
     return Registration(true, 신규.createdAt)
  catch DataIntegrityViolationException:              -- 경쟁에서 졌다
     경쟁자 = userRepository.findByAnonymousKey        -- 자체 트랜잭션 ③ → 새 스냅샷
     return Registration(false, 경쟁자.createdAt)      -- 사용자에겐 "기존 사용자"로 보인다
```

#### 왜 바깥 트랜잭션을 없애는 것이 옳은가

- **바깥 트랜잭션은 아무것도 지키고 있지 않았다.** 읽기 1회 · 쓰기 1회(별도 트랜잭션) · 읽기 1회뿐이고,
  불변식(§6-1)을 실제로 지키는 것은 **DB 의 UNIQUE 제약**이다. 지키는 것 없이 **스냅샷만 붙잡아**
  함정 ④를 만들고 있었다.
- 각 리포지토리 호출이 자기 트랜잭션을 가지므로 ③은 **항상 최신 커밋을 본다.**
  → **정합성이 DB 격리 수준에 의존하지 않는다.** H2 와 MySQL 의 기본 격리가 달라도 결과가 같다.
  이것이 이 선택의 핵심 이득이다 — 함정 ④의 "테스트는 통과, 운영은 실패" 구조가 **원천 제거**된다.
- ②의 `REQUIRES_NEW` 는 바깥에 트랜잭션이 없으면 `REQUIRED` 와 동작이 같다. 그래도 **`REQUIRES_NEW` 를
  유지**한다 — 나중에 누가 `register` 를 트랜잭션 안에서 부르더라도 **쓰기 실패가 그 트랜잭션을
  오염시키지 않게**(함정 ②) 방어선을 남기기 위해서다.

⚠️ **전제**: `register` 는 **트랜잭션 밖에서 호출**되어야 한다. 트랜잭션 안에서 부르면 ③이 다시
호출자의 스냅샷에 갇혀 함정 ④가 되살아난다. 유일한 호출 예정자인 `bootstrap`(§2-2)은 두 모듈의
결과를 합치기만 하므로 트랜잭션을 열 이유가 없다 — **집계 모듈에 `@Transactional` 을 붙이지 않는다.**

#### ⚠️ `UserWriter` 를 별도 빈으로 분리해야 하는 이유 (이 설계의 급소)

`@Transactional` 은 **프록시 기반**이다. `AuthService` 안에서 `this.insert(...)` 로 자기 메서드를
부르면(self-invocation) 프록시를 거치지 않아 **`REQUIRES_NEW` 가 걸리지 않고 바깥 트랜잭션 그대로**
실행된다. 그러면 §6-2 **함정 ②가 그대로 재현**되어 커밋 시점에 `UnexpectedRollbackException` 이 터진다.

→ 즉 “`REQUIRES_NEW` 를 쓴다”는 문장만으로는 이 설계가 성립하지 않는다.
**삽입을 `auth/internal/UserWriter` 라는 다른 빈으로 옮기고 `AuthService` 가 주입받아 호출**해야
비로소 §6-3 의 채택 근거가 유효해진다. `@Lazy` 자기 주입도 가능하지만, 의존이 눈에 보이는 별도 빈이
읽기 쉽고 테스트에서 경계를 확인하기도 쉽다.

⚠️ 이 조건이 깨지면 `AuthConcurrencyTest`(§10)가 실패한다 — **테스트가 이 설계의 감시자**다.

**판정 매트릭스**

| 상황 | ① 조회 TX | ② 삽입 TX | ③ 재조회 TX | 응답 |
|---|---|---|---|---|
| 기존 사용자 | 커밋 (행 있음) | 실행 안 함 | 실행 안 함 | `newUser=false` |
| 최초 등록 | 커밋 (행 없음) | 커밋 | 실행 안 함 | `newUser=true` |
| 경쟁에서 짐 | 커밋 (행 없음) | **롤백 — 여기서 끝난다** | 커밋 (경쟁자 행 조회) | `newUser=false` (200) |

- **경쟁에서 져도 사용자에게는 아무 일도 일어나지 않는다.** 500 이 아니라 정상 200 이고,
  화면상 "이미 등록된 사용자"와 구분되지 않는다.
- **③ 이 반드시 행을 찾는 근거**: InnoDB 는 중복 키 삽입 시 상대 트랜잭션이 끝날 때까지 대기시킨다.
  ② 가 UNIQUE 위반을 받았다는 것은 **경쟁자가 이미 커밋했다**는 뜻이므로, 새 트랜잭션인 ③ 은
  그 행을 반드시 본다.
- **DB 이식성**: `REQUIRES_NEW` 와 UNIQUE 위반 → `DataIntegrityViolationException` 변환은 Spring 표준
  예외 변환이라 H2·MySQL 이 동일하게 동작한다. **네이티브 SQL 도, 격리 수준 가정도 쓰지 않는 것**이
  이 선택의 핵심 이득이다(§6-3 기각안 대비).
- ⚠️ 트랜잭션(커넥션)을 최대 3회 여닫는다. 대부분의 요청은 ① 하나로 끝나고(재방문), ②·③ 은
  생애 최초 진입에만 발생한다 — **의도적으로 수용**한다.

### 6-5. 의도적으로 수용한 것

- **`newUser` 는 “누가 먼저였는가”에 따라 갈린다.** C1 에서 진 쪽은 방금 만들어진 사용자인데도
  `false` 를 받는다. 온보딩 노출 분기(auth.md §5-2)가 어긋날 수 있으나, 같은 사용자의 중복 호출이라
  **온보딩을 두 번 띄우지 않는 쪽이 오히려 바람직하다.**
- **`register` 는 원자적이지 않다.** ①·②·③ 이 각각 별개 트랜잭션이므로 중간 상태가 외부에 보인다.
  감쌀 불변식이 없으므로(§6-4) 문제가 되지 않지만, **나중에 `register` 에 두 번째 쓰기가 추가되면
  이 전제가 깨진다.** 그때는 트랜잭션 경계를 다시 설계해야 하며 함정 ④가 함께 돌아온다 —
  이 문단이 그 경고다.
- **호출자가 트랜잭션을 열지 않는다는 전제에 기대고 있다**(§6-4). 런타임 가드
  (`TransactionSynchronizationManager` 로 활성 트랜잭션 감지 후 실패)를 둘 수도 있으나,
  방어 코드가 늘고 그 라인을 덮을 테스트가 또 필요하다. **MVP 에서는 문서 + 리뷰로 지킨다** —
  호출자가 `bootstrap` 하나뿐이라 감시 비용이 낮다. 호출자가 늘면 재검토한다.

---

## 7. 기존 코드 리팩터링

**전수 목록.** 이 도메인은 기존 보안 코드를 직접 건드린다.

| 파일 | 변경 | 영향 |
|---|---|---|
| [SecurityConfig.java](../../src/main/java/kang20/ytcreator/config/SecurityConfig.java) | `anyRequest().permitAll()` → **공개 경로 열거 + `anyRequest().authenticated()`**, `exceptionHandling` 에 진입점 등록 | ⚠️ **가장 파급이 크다.** 이후 모든 엔드포인트가 기본 차단된다. 공개로 열어야 할 경로를 빠뜨리면 그 기능이 통째로 401 이 된다 |
| [AnonymousKeyFilter.java](../../src/main/java/kang20/ytcreator/shared/security/AnonymousKeyFilter.java) | 형식 검증 + 거부 사유 attribute 추가 | 기존 "거부하지 않는다" 원칙은 **유지**. `HEADER` 상수도 그대로(프론트 계약) |
| [AnonymousAuthentication.java](../../src/main/java/kang20/ytcreator/shared/security/AnonymousAuthentication.java) | **주석만** 갱신 — *"로그인이 필요한 기능은 `appLogin()` 경로를 별도로 둔다"* 삭제 | 코드 동작 무변경. auth.md §4-1·§10-5 가 지적한 결정 이전 서술 |
| [AnonymousKeyFilterTest.java](../../src/test/java/kang20/ytcreator/shared/security/AnonymousKeyFilterTest.java) | 형식 위반·attribute 케이스 추가 | 기존 3케이스는 그대로 통과해야 한다(회귀 감지선) |
| [ControllerTest.java](../../src/test/java/kang20/ytcreator/base/ControllerTest.java) | **변경 없음이 정책** | 단 `@Import(SecurityConfig.class)` 라 **모든 컨트롤러 슬라이스 테스트가 default-deny 를 그대로 받는다.** 이후 도메인의 컨트롤러 테스트는 익명키 헤더를 붙여야 200 이 난다 — 선례로 기록한다 |
| [common.adoc](../../src/docs/asciidoc/common.adoc) | 인증 절 갱신 — *"내부 식별 전용"*, *"로그인이 필요한 기능은 `appLogin()`"* 삭제 | **프론트가 읽는 문서**다. 방치하면 폐기된 로그인 경로를 프론트가 구현한다 |
| [index.adoc](../../src/docs/asciidoc/index.adoc) | **이번엔 변경 없음** | auth 는 컨트롤러가 없어 include 할 adoc 이 없다(§8) |
| [ErrorCode.java](../../src/main/java/kang20/ytcreator/shared/exception/ErrorCode.java) | **변경 없음이 정책** | auth.md §7 — 신규 코드 0건. `AUTH_001`·`AUTH_002` 를 그대로 쓴다 |
| [toss-integration.md](../rule/toss-integration.md) | "익명키 흐름" 3단계의 verify 서술 삭제 | 규칙 문서가 폐기된 설계를 지시하고 있다(auth.md §10-4) |

- **삭제된 요구가 남긴 코드는 없다.** verify 폐기(auth.md §4-3)는 초안 단계 결정이라 구현된 적이 없다.

---

## 8. API 계약 · REST Docs

계약 상세는 [auth.md](./auth.md) §5 가 정본. 여기는 스니펫 매핑만 둔다.

| 메서드 | 경로 | 인증 | 스니펫 ID |
|---|---|---|---|
| — | **auth 는 HTTP 엔드포인트가 없다**(auth.md §5-3) | — | **없음** |

- **이번 구현에서 새로 만드는 adoc 이 없다.** `src/docs/asciidoc/auth.adoc` 도, `index.adoc`
  include 도 추가하지 않는다. 없는 엔드포인트를 문서화할 수 없다.
- ⚠️ **그 결과 게이트의 401 두 종류가 REST Docs 에 실리지 못한다.** 프론트 계약인데 문서에 없는
  상태가 된다. 해소 경로는 둘 중 하나이며 **후자를 택한다**:

| 안 | 판정 | 이유 |
|---|---|---|
| 문서화 전용 더미 컨트롤러를 만든다 | ❌ | 운영 코드에 문서용 엔드포인트가 남는다 |
| **`bootstrap` 구현 시 그 엔드포인트의 실패 케이스로 문서화**(`bootstrap-entry-fail-*`) | ✅ | 게이트는 어차피 실제 엔드포인트를 통해서만 관측된다. 중복도 없다 |

- 따라서 **`/docs-sync` 는 이번 구현 후 실행 대상이 아니다** — 갱신될 스니펫이 없다.
  `common.adoc` 문구 수정(§7)만 다음 `/docs-sync` 때 함께 반영한다.
- 네이밍 규약은 [rest-docs.md](../rule/rest-docs.md).

---

## 9. 에러 코드

**추가 없음.** [auth.md](./auth.md) §7 확정대로 기존 게이트로 전부 커버된다.

| enum | 쓰임 | 신설 |
|---|---|---|
| `AUTH_001` | 익명키 헤더 없음 → 진입점이 응답 | ❌ 기존 |
| `AUTH_002` | 익명키 형식 위반 → 진입점이 응답 | ❌ 기존 |
| `COMMON_002` | 등록 실패 등 예기치 못한 오류 → `GlobalExceptionHandler` 자동 | ❌ 기존 |

- `ErrorCode` 에 `AUTH` 섹션은 **이미 존재**한다. 이번에 손대지 않는다.
- `AUTH_003`(403)은 auth 가 쓰지 않는다(auth.md §4-2).
- 네이밍은 [error-handling.md](../rule/error-handling.md).

---

## 10. 테스트 계획

| 테스트 | 종류 | 핵심 케이스 |
|---|---|---|
| `AuthServiceTest` | `@ApplicationModuleTest` | 최초 등록 `newUser=true` / 재호출 `newUser=false` + 사용자 1명 유지(멱등, U2) / `registeredAt` == `createdAt` |
| `AuthConcurrencyTest` | 비TX 멀티스레드 | **C1** — 같은 익명키 동시 N회 등록 → 사용자 정확히 1명, `newUser=true` 는 정확히 1회, **예외·500 없음**(§6-4). `register` 를 **트랜잭션 밖에서** 호출해야 실제 경쟁이 재현된다 |
| `AuthTransactionBoundaryTest` | 단위(리플렉션) | **함정 ④ 회귀 방지** — `AuthService.register` 에 `@Transactional` 이 **붙어 있지 않음**을, `UserWriter.insert` 가 **`REQUIRES_NEW`** 임을 단언한다. 사람이 무심코 붙이는 순간 실패한다 |
| `AnonymousKeyFilterTest` | 단위 (기존 확장) | 정상/공백/없음(기존 3케이스 유지) + 형식 위반 시 인증 미설정 & attribute `MALFORMED` + 정상 시 attribute 없음 |
| `AnonymousKeyEntryPointTest` | 단위 | attribute `MALFORMED` → `AUTH_002` / `MISSING` → `AUTH_001` / attribute 없음 → `AUTH_001` / 응답이 `ErrorResponse` JSON·401 · **본문에 익명키 원문 없음**(U6) |
| `AnonymousKeyFormatTest` | 단위 | `isValid` 경계값 / `mask` 가 앞 4자만 남김 · 4자 미만 입력에서도 원문이 새지 않음(U6) |
| `SecurityGateTest` | `@SpringBootTest` + MockMvc | 게이트 통합 — 공개 경로는 헤더 없이 200 / 보호 경로는 헤더 없이 401 `AUTH_001` / 형식 위반 401 `AUTH_002` / 정상 헤더 200 |
| `ModularityTest` | 구조 (기존) | `auth` 의 `allowedDependencies` 가 `shared` 뿐임을 `verify()` 가 강제. **`auth → subscription` 이 생기면 즉시 실패**(§2-2 불변식) |

**어떤 테스트가 어떤 라인을 덮는가**

| 산출물 | 덮는 테스트 | 비고 |
|---|---|---|
| `auth/AuthService.java` | `AuthServiceTest` + `AuthConcurrencyTest` | catch 분기는 동시성 테스트로만 도달한다 — 단위로는 못 덮는다 |
| `auth/internal/User.java` | `AuthServiceTest` | 생성자·게터 |
| `auth/internal/UserRepository.java` | `AuthServiceTest` | 인터페이스(구현 라인 없음) |
| `auth/internal/UserWriter.java` | `AuthServiceTest` + `AuthConcurrencyTest` | 정상 삽입 + 경쟁 시 롤백 경계(§6-4) |
| `auth/dto/Registration.java` | — | `dto/**` 커버리지 제외([testing.md](../rule/testing.md)) |
| `shared/security/AnonymousKeyFilter.java` | `AnonymousKeyFilterTest` | 3분기 전부 |
| `shared/security/AnonymousKeyEntryPoint.java` | `AnonymousKeyEntryPointTest` + `SecurityGateTest` | 분기 + 직렬화 |
| `shared/security/AnonymousKeyFormat.java` | `AnonymousKeyFormatTest` | 경계값 |
| `config/SecurityConfig.java` | `SecurityGateTest` (동작) | 라인 커버리지는 `config/**` 제외 대상. **제외라도 동작은 반드시 검증한다** — 계약이기 때문 |

- **이번 변경 파일은 라인 100%** 를 채운다. 전역 게이트 수치는 [testing.md](../rule/testing.md) 가 정본이며
  그건 레포 하한일 뿐이다.
- 도달 불가 라인을 남기지 않는다. `AuthService` 의 catch 분기는 동시성 테스트로 **실제 경쟁을 만들어**
  덮는다 — 목으로 예외를 흉내 내면 §6-4 의 트랜잭션 경계가 검증되지 않는다.
- ⚠️ **수용한 검증 한계**: H2 기본 격리는 `READ COMMITTED`, MySQL 은 `REPEATABLE READ` 라
  **격리 수준 차이 자체는 테스트로 재현할 수 없다.** §6-4 채택안이 격리 수준에 의존하지 않도록 설계된
  이유가 이것이다 — 재현할 수 없는 차이는 **의존하지 않는 것**으로만 막을 수 있다.
  `AuthTransactionBoundaryTest` 가 그 설계 전제(바깥 트랜잭션 없음)를 지키는 감시자 역할을 한다.
- 시간 검증은 `Clock` 빈([TimeConfig](../../src/main/java/kang20/ytcreator/config/TimeConfig.java))을
  고정해 수행한다 — `LocalDateTime.now()` 직접 호출 금지([testing.md](../rule/testing.md)).

---

## 11. 구현 단계 (체크리스트)

- [ ] `chore(db)`: `backend/deploy/sql/event-publication-v1.sql` — 아웃박스 테이블 (§3-1).
      **`ddl-auto: validate` 가 미사용 엔티티까지 검증하는지 실제로 확인하고 결과를 §3-1 에 역반영**
- [ ] `chore(db)`: `backend/deploy/sql/auth-v1.sql` — `users` 테이블 + UNIQUE 인덱스 (§3-1)
- [ ] `feat(auth)`: `auth` 모듈 골격 + `AuthService` + `UserWriter` + `internal/` (§4 매핑대로)
- [ ] `feat(auth)`: `shared/security` 확장 — `AnonymousKeyFormat`·`AnonymousKeyEntryPoint`, 필터 변경 (§5-2)
- [ ] `feat(auth)`: `SecurityConfig` 기본 인증 필요로 전환 + 진입점 등록 (§7)
- [ ] `test(auth)`: §10 전 항목 — 동시성 테스트 포함
- [ ] `refactor(auth)`: `AnonymousAuthentication` 주석 갱신 (§7)
- [ ] `docs(api)`: `common.adoc` 인증 절 갱신 (§7)
- [ ] `docs(rule)`: `toss-integration.md` verify 서술 삭제 (§7)
- [ ] `./gradlew test --tests "*ModularityTest"` — 모듈 경계 통과
- [ ] **변경 파일 라인 커버리지 100% 확인** → `/code-review`
- [ ] `/docs-sync` — ⚠️ **이번엔 스니펫 변화가 없다**(§8). `common.adoc` 수정분만 반영

---

## 12. 결정 필요 (Open Questions)

> 비즈니스 결정은 [auth.md](./auth.md) §9-1 에서 확정됐다. 여기는 **설계 쟁점**만 남긴다.

### 12-1. `bootstrap` 모듈을 이번에 구현할 것인가 — **권고: 미룬다**

- `bootstrap` 의 존재 이유는 `auth` 와 `subscription` 을 합치는 것인데, **`subscription` 이 없다.**
  지금 만들면 auth 만 감싼 껍데기가 되고, `subscription` 필드를 뺀 반쪽 계약이 프론트로 나간다
  → `subscription` 구현 시 **프론트가 같은 화면을 두 번 고친다.**
- ⚠️ **대가**: 이번 구현이 끝나도 **프론트가 호출할 엔드포인트가 하나도 없다.** auth 는 순수 기반
  작업이며 단독으로는 출시 가치가 없다. 이 점을 인정하고 진행할지 확인이 필요하다.
- 대안(반쪽 `bootstrap` 을 먼저 내보내기)을 택한다면 §8 의 REST Docs 판단과 §11 순서가 함께 바뀐다.

### 12-2. `anonymousKey` 컬럼 길이 — auth.md 🔶-3 이 데이터 모델에 닿는다

- auth.md 🔶-3 은 *"설계 착수를 막지 않는다 — 데이터 모델·모듈 매핑·흐름 어디에도 영향이 없다"* 고
  적었으나, **컬럼 길이와 `isValid` 상수에는 직접 닿는다.** 그 서술은 과했다.
- 실질 영향은 작다 — 255 로 시작하고 스파이크 6 이후 조정하면 된다. 다만 조정 시
  `ALTER TABLE` 수동 DDL 이 필요하다(§3-1). **길이를 줄이는 방향이면 기존 행 검증이 선행**되어야 한다.
- 형식 규칙(`isValid`)은 스파이크 전까지 **"공백 아님 + 255자 이하"** 최소선으로만 구현하고,
  문자셋 제한은 넣지 않는다 — 실제 hash 문자셋을 모른 채 좁히면 정상 사용자를 막는다.

### 12-3. `registeredAt` 의 직렬화 형식 — 기존 문서 두 개가 서로 어긋난다

| 출처 | 지시 |
|---|---|
| [rest-docs.md](../rule/rest-docs.md) “공통 문서 규격” | 시간대: **UTC ISO-8601** |
| [common.adoc](../../src/docs/asciidoc/common.adoc) “시간대” | 서버 기준 시간대는 **`Asia/Seoul`** |
| [TimeConfig](../../src/main/java/kang20/ytcreator/config/TimeConfig.java) | `Clock.system(KST)` |
| [BaseTimeEntity](../../src/main/java/kang20/ytcreator/shared/domain/BaseTimeEntity.java) | `LocalDateTime` — **오프셋 정보가 없다** |

- auth.md §5-2 의 응답 예시는 `"2026-08-07T12:34:56Z"`(UTC) 인데, `LocalDateTime` 을 그대로
  직렬화하면 `"2026-08-07T12:34:56"`(오프셋 없음)이 나간다. **셋이 서로 다르다.**
- `registeredAt` 은 디버깅·문의 대응용이라 이 도메인 안에서는 영향이 작지만, **시각 표기는 전 도메인
  공통 계약**이라 여기서 정하면 그대로 선례가 된다. `subscription` 의 만료 시각, 작업 목록의 생성
  시각처럼 **사용자에게 보이는 시각**이 나오는 순간 이 결정이 화면을 바꾼다.
- 이 설계서에서 임의로 정하지 않는다. `docs/server/api-spec.md` 신설(auth.md §10-7) 시
  **공통 규약으로 한 번에 정하기**를 권한다.

---

## 13. 버전 이력

v1 이므로 없음(v2 이상에서 작성).
