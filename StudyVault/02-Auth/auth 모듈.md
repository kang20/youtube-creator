---
module: auth
path: backend/src/main/java/kang20/ytcreator/auth
keywords: authentication, anonymous-key, idempotent-registration, module-api
---

# auth 모듈 (★★★)

#module-auth #arch-module-boundary #pattern-internal-package

## 목적

익명키 하나로 사용자를 **등록·식별**한다. 이 모듈은 **"이 익명키에 해당하는 사용자가 존재한다"는 사실**에만 권위를 갖는다 — 구독도, 결제도, 작업도 알지 않는다.

**이 레포의 첫 도메인 모듈**이라, 이후 모든 모듈의 선례가 된다.

## 주요 파일

| 파일 | 역할 |
|---|---|
| `auth/package-info.java` | `@ApplicationModule(displayName="인증", allowedDependencies={"shared"})` |
| `auth/AuthService.java` | **모듈 밖에서 부를 수 있는 유일한 타입** |
| `auth/dto/Registration.java` | 반환 record — `newUser`, `registeredAt` |
| `auth/internal/User.java` | 엔티티. `anonymous_key_hash VARCHAR(64)` UNIQUE |
| `auth/internal/UserRepository.java` | `findByAnonymousKeyHash` |
| `auth/internal/UserWriter.java` | **별도 빈** — `@Transactional(REQUIRES_NEW) insert` |
| `auth/internal/AnonymousKeyHasher.java` | SHA-256 hex 64자 |

## 공개 인터페이스

| 노출 | 종류 | 설명 |
|---|---|---|
| `AuthService.register(String)` | 메서드 | 익명키에 해당하는 사용자를 보장한다 (**멱등**) |
| `Registration` | record | `(boolean newUser, LocalDateTime registeredAt)` |

> [!important] 딱 두 개다
> `User`·`UserRepository`·`UserWriter`·`AnonymousKeyHasher` 는 전부 `internal/` 이다.
> 다른 모듈이 참조하면 `ModularityTest` 가 깨진다.
>
> `Registration` 에 **사용자 PK 를 담지 않는다** — 담는 순간 다른 모듈이 auth 의 PK 를 들고 다니게 된다.

## 내부 흐름

```text
register(anonymousKey)                      ⚠️ @Transactional 없음 (의도적)
  │
  ⓪ hasher.hash(원문) ────────────▶ 이후로는 원문을 쓰지 않는다
  │
  ① findByAnonymousKeyHash        [자체 트랜잭션]
  │     있음 ──▶ Registration(newUser=false, 기존.createdAt)
  │     없음
  │      ▼
  ② userWriter.insert(hash)       [REQUIRES_NEW — 별도 빈]
  │     성공 ──▶ Registration(newUser=true, 신규.createdAt)
  │     UNIQUE 위반 (경쟁에서 짐)
  │      ▼
  ③ findByAnonymousKeyHash        [자체 트랜잭션 → 새 스냅샷]
        ──▶ Registration(newUser=false, 경쟁자.createdAt)   ← 에러 아님, 정상 200
```

### 이 흐름의 급소 3가지

> [!warning] ① `register` 에 `@Transactional` 을 붙이면 안 된다
> 바깥 트랜잭션을 열면 MySQL InnoDB 의 `REPEATABLE READ` 스냅샷이 **첫 조회 시점에 고정**되어,
> ③ 재조회가 경쟁자가 커밋한 행을 **보지 못한다.**
> 이 결함은 H2(`READ COMMITTED`)에서는 **재현되지 않고 운영 MySQL 에서만** 터진다.
> → 바깥 트랜잭션은 지키는 불변식도 없다. 불변식은 **DB 의 UNIQUE 제약**이 지킨다.

> [!warning] ② `UserWriter` 는 반드시 **다른 빈**이어야 한다
> `@Transactional` 은 프록시 기반이다. `this.insert(...)` 로 자기 메서드를 부르면(self-invocation)
> 프록시를 우회해 **`REQUIRES_NEW` 가 안 걸린다.** 그러면 제약 위반이 호출자 트랜잭션을
> rollback-only 로 오염시킨다. → [[스프링-트랜잭션]]

> [!warning] ③ 익명키를 **원문으로 저장하면 안 된다**
> UNIQUE 위반을 정상 흐름으로 삼는 설계라, 위반 메시지에 저장 값이 실려 **로그로 나간다.**
> ```
> WARN ... Unique index violation ... VALUES ( 'toss-anon-...' )   ← 원문이면 유출
> ```
> SHA-256 해시로 저장하면 WARN 은 그대로 남고 **값만 해시**가 된다 — 진단은 살고 노출은 막힌다.

## 의존

| 방향 | 모듈 | 경유 |
|---|---|---|
| **사용** | `shared` | `BaseTimeEntity`, `ErrorCode` 등 (OPEN 모듈) |
| **사용됨** | `bootstrap` *(미구현)* | `AuthService.register` 직접 호출 |
| **절대 안 함** | `subscription` | ⛔ 참조하면 순환의 씨앗 |

```java
allowedDependencies = {"shared"}   // 하나뿐이다
```

> [!important] 게이트는 auth 의 것이 아니다
> 익명키 필터·진입점·형식검증은 `auth` 가 아니라 **`shared/security`** 에 있다.
> 게이트는 전 도메인 공통 장치이고, `auth` 에 두면 `config → auth` 의존이 생긴다.
> → [[shared 모듈]] · [[config 모듈]]

## 설정

| 항목 | 값 | 비고 |
|---|---|---|
| 테이블 | `users` | 수동 DDL `backend/deploy/sql/auth-v1.sql` |
| 컬럼 | `anonymous_key_hash VARCHAR(64)` UNIQUE | **`CHAR` 로 쓰면 `validate` 가 거부한다** |
| 운영 스키마 | `ddl-auto: validate` | 배포로 만들어지지 않는다 — DDL 이 **먼저** |

## 테스트

```bash
cd backend
./gradlew test --tests "*Auth*"
```

| 테스트 | 종류 | 무엇을 지키나 |
|---|---|---|
| `AuthServiceTest` | `@ApplicationModuleTest` | 멱등 등록, `registeredAt == createdAt` |
| `AuthConcurrencyTest` | 비TX 멀티스레드 | 동시 등록 경쟁 → 사용자 1명 · `newUser=true` 1회 · **로그에 원문 없음** |
| `AuthTransactionBoundaryTest` | 단위(리플렉션) | `register` 에 `@Transactional` **부재**, `insert` 가 `REQUIRES_NEW` |
| `AuthModuleBoundaryTest` | 구조 | 컨트롤러 부재 · `allowedDependencies` · **수동 DDL ↔ 매핑 대조** |
| `AnonymousKeyHasherTest` | 단위 | NIST 표준 벡터 고정 |

> [!tip] `AuthTransactionBoundaryTest` 를 눈여겨봐라
> 급소 ①은 H2 에서 통과하고 MySQL 에서만 터지므로 **기능 테스트로는 원리상 못 잡는다.**
> 그래서 "어노테이션이 **없음**"을 리플렉션으로 단언한다.
> **재현할 수 없는 차이는 "의존하지 않는 것"으로만 막을 수 있다.**

## 설계된 미래 — `bootstrap` 집계 모듈

아직 구현되지 않았지만 설계는 확정돼 있다.

```text
POST /api/v1/bootstrap
   └─▶ bootstrap
          ├─▶ AuthService.register(...)         → newUser, registeredAt
          └─▶ SubscriptionService.statusOf(...) → 구독 상태
```

**auth 에 컨트롤러가 없는 이유**가 이것이다. `auth` 가 진입 응답을 조립하면 결제를 알게 되고,
이후 다른 도메인이 진입 시 뭔가를 더 필요로 할 때마다 같은 논리로 얹혀 **`auth` 가 서서히 홈 화면 API 가 된다.**

## 관련 노트

- [[shared 모듈]] — 게이트가 사는 곳
- [[config 모듈]] — 게이트를 조립하는 곳
- [[Spring Modulith 아키텍처]]
- [[모듈 경계 검증]] — `AuthModuleBoundaryTest` 의 배경
- [[모듈 간 통신 — 이벤트 우선]] — 이벤트를 만들지 않기로 한 근거
- [[스프링-트랜잭션]] — 급소 ①②의 이론
- [[Modulith 연습문제]]
