---
module: exercises
path: 06-Exercises
keywords: practice, onboarding, spring-modulith, module-boundary
---

# Spring Modulith — 온보딩 연습문제

#practice #onboarding #arch-modulith

> 20문항. **답을 보기 전에 실제 코드를 열어 확인해라** — 파일 경로가 문제에 다 적혀 있다.

## 관련 모듈

- [[Spring Modulith 아키텍처]] · [[모듈 경계 검증]] · [[모듈 간 통신 — 이벤트 우선]]
- [[auth 모듈]] · [[shared 모듈]] · [[config 모듈]] · [[빌드와 검증 파이프라인]]

---

# A. 아키텍처와 경계

## A1 — 코드 읽기 [trace]

> `backend/src/main/java/kang20/ytcreator/auth/` 아래에서, **다른 모듈이 참조할 수 있는 타입**을 전부 골라라. 근거가 되는 파일도 대라.

> [!answer]- 정답 보기
> **`AuthService` 와 `dto/Registration` 둘뿐이다.**
> 1. `auth/package-info.java` — `@ApplicationModule` 이 선언돼 있고 `type` 을 안 줬으므로 기본 `CLOSED` 다.
> 2. `CLOSED` 모듈은 **모듈 루트 패키지의 public 타입만** 노출한다.
> 3. `internal/` 의 `User`·`UserRepository`·`UserWriter`·`AnonymousKeyHasher` 는 `public` 이어도 밖에서 참조하면 `ModularityTest` 가 깨진다.
> 4. `dto/` 는 하위 패키지지만 `Registration` 이 `AuthService.register` 의 **반환 타입**이라 함께 공개된다.

## A2 — 코드 읽기 [trace]

> `allowedDependencies` 를 아예 적지 않으면 어떻게 되는가? 빈 배열 `{}` 과 차이가 있는가?

> [!answer]- 정답 보기
> **차이 없다 — 둘 다 완전 격리다.** 아무 모듈도 부를 수 없다.
> 그래서 새 의존을 추가할 때 반드시 여기 적게 되고, 적으면서 **"이 결합이 정말 필요한가"를 되묻게** 만드는 게 이 구조의 요점이다.
> ⚠️ 단 `shared` 처럼 `Type.OPEN` 인 모듈은 **참조당하는** 쪽이라 이 설정과 무관하다.

## A3 — 설계 판단 [config]

> `shared` 를 `OPEN` 으로 둔 대가는 무엇이고, 그 대가를 어떻게 통제하는가?

> [!answer]- 정답 보기
> **대가**: 하위 패키지까지 전부 노출되므로 Modulith 의 경계 강제가 이 모듈에는 적용되지 않는다.
> **통제**: `shared/package-info.java` 에 적힌 규율 — *"여기에는 **도메인 지식이 없는 것만** 둔다. 특정 도메인 냄새가 나면 그 모듈로 옮긴다."*
> 이 규율이 없으면 `shared` 는 쓰레기통이 되고, 모든 모듈이 그걸 참조하므로 **경계가 사실상 사라진다.** 도구가 아니라 사람이 지켜야 하는 유일한 지점이다.

## A4 — 디버깅 [debug]

> `ModularityTest` 가 *"module not declared"* 로 실패한다. 무엇을 확인하고 어떻게 고치는가?

> [!answer]- 정답 보기
> 1. `kang20.ytcreator` 바로 아래에 **`package-info.java` 가 없는 패키지**가 있는지 본다.
> 2. Modulith 는 루트 바로 아래 패키지를 전부 모듈 후보로 보므로, 선언이 없으면 실패한다.
> 3. 선택지 둘:
>    - 그게 **독립 모듈**이면 → `package-info.java` 에 `@ApplicationModule` 추가
>    - 그게 **공용 유틸**이면 → `shared` 안으로 옮긴다
> 4. 애매하면 "이 패키지가 자기 도메인 권위를 갖는가"로 판단한다.

## A5 — 확장 [extend]

> `subscription` 모듈을 새로 만든다. `package-info.java` 를 어떻게 쓰고, **하면 안 되는 것**은 무엇인가?

> [!answer]- 정답 보기
> ```java
> @ApplicationModule(displayName = "구독", allowedDependencies = {"shared"})
> package kang20.ytcreator.subscription;
> ```
> **하면 안 되는 것**: `auth` 의 `allowedDependencies` 에 `"subscription"` 을 추가하는 것.
> - `verify()` 는 **적어 넣으면 정상으로 본다** — 막아주지 않는다.
> - `AuthModuleBoundaryTest.허용_의존은_shared_하나뿐이다` 가 여기서 먼저 빨개진다.
> - 진입 응답을 합쳐야 하면 `auth` 를 고치지 말고 **제3의 집계 모듈**을 둔다.
>
> `subscription` 이 사용자를 **익명키로** 키를 잡을지 `auth` 의 식별자를 참조할지는 자유다 — 어느 쪽이든 `auth` 가 `subscription` 을 모르므로 순환은 안 생긴다.

---

# B. auth 모듈

## B1 — 코드 읽기 [trace]

> `AuthService.register("abc")` 를 처음 보는 익명키로 호출했을 때, 관여하는 파일과 메서드를 순서대로 나열해라.

> [!answer]- 정답 보기
> 1. `auth/AuthService.register` — 진입
> 2. `auth/internal/AnonymousKeyHasher.hash` — **원문을 즉시 해시로** (이후 원문 미사용)
> 3. `auth/internal/UserRepository.findByAnonymousKeyHash` — 조회 (자체 트랜잭션) → 없음
> 4. `auth/internal/UserWriter.insert` — **별도 빈 · `REQUIRES_NEW`** 로 삽입
> 5. `auth/internal/User` 생성 → `shared/domain/BaseTimeEntity` 의 `@CreatedDate` 가 `createdAt` 채움
> 6. `auth/dto/Registration(newUser=true, createdAt)` 반환

## B2 — 디버깅 [debug]

> 누군가 `AuthService.register` 에 `@Transactional` 을 붙였다. **로컬 테스트는 전부 통과**한다. 무엇이 문제이고 어떻게 발견되는가?

> [!answer]- 정답 보기
> **문제**: MySQL InnoDB 기본 격리(`REPEATABLE READ`)는 트랜잭션의 **첫 읽기에 스냅샷을 고정**한다.
> 경쟁에서 진 뒤의 ③ 재조회가 **경쟁자가 커밋한 행을 보지 못하고**, `orElseThrow()` 가 터진다.
> **왜 로컬은 통과하나**: H2 기본 격리는 `READ COMMITTED` 라 매번 새로 읽는다.
> 테스트 프로파일의 `MODE=MYSQL` 은 **문법 호환 모드일 뿐 격리 수준을 바꾸지 않는다.**
> → **H2 는 통과하고 운영 MySQL 에서만 터진다.**
> **어떻게 발견되나**: `AuthTransactionBoundaryTest` 가 어노테이션 **부재**를 리플렉션으로 단언하므로 **즉시 빨개진다.**
> 재현할 수 없는 차이는 "의존하지 않는 것"으로만 막을 수 있고, 그 전제를 테스트가 감시한다.

## B3 — 디버깅 [debug]

> `UserWriter` 를 없애고 그 로직을 `AuthService` 안의 `private @Transactional(REQUIRES_NEW) insert()` 로 옮겼다. 무슨 일이 생기는가?

> [!answer]- 정답 보기
> **`REQUIRES_NEW` 가 걸리지 않는다.** `@Transactional` 은 프록시 기반이라, 같은 빈 안에서 `this.insert(...)` 로 부르면(self-invocation) **프록시를 우회**해 어드바이스가 적용되지 않는다.
> 결과: 삽입이 호출자와 같은 트랜잭션에서 실행되고, UNIQUE 위반 시 그 트랜잭션에 **rollback-only 표식**이 찍힌다.
> `catch` 로 예외를 잡아도 표식은 안 지워지므로 커밋 시점에 `UnexpectedRollbackException` 이 터진다.
> **예외를 잡는 것과 트랜잭션을 살리는 것은 별개다.** → [[스프링-트랜잭션]]

## B4 — 확장 [extend]

> `auth` 에 `GET /api/v1/users/me` 를 추가하라는 요청이 왔다. 어떻게 대응하는가?

> [!answer]- 정답 보기
> **먼저 거절 근거를 확인한다.** `auth` 는 자체 HTTP 엔드포인트를 두지 않기로 **설계 확정**돼 있고, `AuthModuleBoundaryTest.auth_에는_컨트롤러가_없다` 가 이를 강제한다.
> **이유**: `auth` 는 모든 요청이 지나가는 최하부 모듈이다. 여기에 엔드포인트가 생기면 이후 다른 도메인이 진입 시 뭔가를 더 필요로 할 때마다 같은 논리로 얹혀 **`auth` 가 서서히 홈 화면 API 가 된다.**
> **올바른 대응**: 집계 모듈(`bootstrap`)에 엔드포인트를 만들고 `AuthService` 를 호출해 조립한다.
> 정말 `auth` 에 둬야 한다면 그건 **설계 변경**이므로 유스케이스·설계서부터 고친다.

## B5 — 설정 [config]

> 해시 알고리즘을 SHA-512 로 바꾸려 한다. 어떤 파일들이 바뀌어야 하고, 무엇이 먼저 실패하는가?

> [!answer]- 정답 보기
> **먼저 실패하는 것**: `AuthModuleBoundaryTest.컬럼_길이는_해시_출력_길이와_같다`.
> SHA-512 hex 는 128자인데 컬럼은 `VARCHAR(64)` 라, 이 테스트가 없었으면 **모든 등록이 500** 이 됐을 것이다.
> **바꿔야 할 것**:
> 1. `auth/internal/AnonymousKeyHasher` — 알고리즘
> 2. `auth/internal/User` — `@Column(length = 128)`
> 3. `backend/deploy/sql/auth-v1.sql` — `VARCHAR(128)`
> 4. `AnonymousKeyHasherTest` — NIST 표준 벡터
>
> ⚠️ **운영 데이터가 있으면 훨씬 비싸다** — 기존 해시는 원문을 모르므로 **재계산이 불가능**하다. 사실상 전 사용자 재등록이다.
> ⚠️ `AnonymousKeyFormat.MAX_LENGTH`(입력 원문 상한)는 **건드리지 않는다** — 저장 길이와 입력 검증은 다른 축이다.

---

# C. shared 모듈

## C1 — 코드 읽기 [trace]

> 익명키 헤더 없이 보호 경로에 요청하면 401 `AUTH_001` 이 나간다. 그 응답 본문을 **누가** 만드는지 추적해라.

> [!answer]- 정답 보기
> 1. `shared/security/AnonymousKeyFilter` — 헤더 없음 → **거부하지 않고** attribute 에 사유만 남기고 체인 계속
> 2. `config/SecurityConfig` 인가 규칙 — `anyRequest().authenticated()` 에서 차단
> 3. `shared/security/AnonymousKeyEntryPoint.commence` — attribute 를 읽어 `AUTH_001`/`AUTH_002` 를 가르고 **`ErrorResponse` 를 직접 write**
>
> ⚠️ `GlobalExceptionHandler` 는 **관여하지 않는다.** `@RestControllerAdvice` 는 DispatcherServlet 이후인데 미인증 요청은 그 앞에서 끝난다.

## C2 — 디버깅 [debug]

> 401 은 나가는데 **응답 본문이 비어 있다.** 어디를 보는가?

> [!answer]- 정답 보기
> 1. `config/SecurityConfig` 에 `exceptionHandling(... AnonymousKeyEntryPoint ...)` 등록이 **누락**됐는지 확인한다.
> 2. 없으면 Spring Security 기본 401(본문 없음)이 나간다.
> 3. **파급**: 프론트의 `AUTH_001`(SDK 재호출) / `AUTH_002`(안내 후 종료) 분기가 **전부 죽는다.** 코드로 구분하던 두 상황이 하나가 된다.
> 4. `SecurityGateTest` 가 본문의 `code` 까지 단언하므로 정상적으론 여기서 먼저 걸린다.

## C3 — 설계 판단 [config]

> `AnonymousKeyFilter` 가 형식이 틀린 익명키를 **그 자리에서 401 로 끊으면** 안 되는 이유는?

> [!answer]- 정답 보기
> **공개 엔드포인트 계약이 깨진다.** 공개 경로는 *"헤더가 있어도 형식이 틀려도 무시하고 200"* 이어야 한다.
> 필터는 경로 규칙을 모르므로, 거기서 끊으면 공개 경로까지 막힌다.
> → 필터는 **판정하지 않고 사유만 남기고**, 판정은 인가 규칙이 한다.
> 이렇게 하면 **한 필터가 "공개 경로 통과"와 "보호 경로 차단" 두 계약을 동시에** 만족한다.

## C4 — 디버깅 [debug]

> `AnonymousAuthentication.getPrincipal()` 이 마스킹된 값을 반환하도록 "보안 강화" 커밋이 들어왔다. 무슨 일이 생기는가?

> [!answer]- 정답 보기
> **서로 다른 사용자가 같은 앞 4자로 뭉쳐 남의 계정으로 들어간다.**
> `getPrincipal()` 은 **실제 식별**에 쓰이는 값이라 원문이어야 한다. 마스킹해야 하는 것은 **`toString()`**(문자열 표현)뿐이다.
> `AnonymousAuthenticationTest` 가 두 축을 **반대 방향으로** 고정하는 이유가 이것이다:
> | 축 | 값 | 뒤바뀌면 |
> |---|---|---|
> | `toString()` | 마스킹 | 로그에 원문이 샌다 |
> | `getPrincipal()` | **원문** | **인증이 깨진다** |

## C5 — 확장 [extend]

> `ErrorCode` 에 새 도메인의 코드를 추가하려 한다. 절차는?

> [!answer]- 정답 보기
> 1. `shared/exception/ErrorCode.java` 에 `{DOMAIN}_{NNN}` 형식으로 **섹션을 추가**한다 (기존 섹션에 끼워 넣지 않는다).
> 2. 도메인 서비스에서 `throw new BusinessException(ErrorCode.XXX)` 로 던진다 — 서비스는 `ErrorCode` 만 알고 HTTP 상태·응답 포맷은 모른다.
> 3. 컨트롤러 테스트에서 **실패 케이스를 REST Docs 로 문서화**한다 — 실패 응답도 프론트 계약이다.
> 4. 새 `HttpStatus` 가 필요하면 `GlobalExceptionHandler` 에 핸들러를 추가한다.
>
> ⚠️ **코드는 영구 결번이다.** 배포된 코드를 다른 의미로 재사용하면 프론트의 기존 분기가 **조용히 오작동**한다.

---

# D. config · 빌드 · 운영

## D1 — 디버깅 [debug]

> 배포했더니 앱이 기동조차 안 된다: `Schema validation: missing table [event_publication]`. 아무 모듈도 이벤트를 발행하지 않는데 왜인가?

> [!answer]- 정답 보기
> `spring-modulith-starter-jpa` 가 이벤트 아웃박스를 **JPA 엔티티로 등록**하기 때문이다.
> 운영은 `ddl-auto: validate` 라 **퍼시스턴스 유닛에 등록된 모든 엔티티**의 스키마를 검증한다 — 실제 사용 여부와 무관하다.
> **조치**: `backend/deploy/sql/event-publication-v1.sql` 을 **앱 배포보다 먼저** 적용한다.
> ⚠️ 테이블명은 **소문자** `event_publication` 이다. 대문자로 만들면 로컬(H2)에선 안 드러나고 **리눅스 MySQL 에서만** 깨진다.

## D2 — 디버깅 [debug]

> 배포 후 Grafana 알림이 아무것도 안 온다. 앱은 정상이고 에러도 없다. 어디를 보는가?

> [!answer]- 정답 보기
> 1. `curl localhost:8080/actuator/prometheus` → **401** 이 나오는지 확인한다.
> 2. `config/SecurityConfig` 의 `PUBLIC_PATHS` 를 본다. `/actuator/health` 만 열려 있으면 **스크레이프가 전부 401** 이다.
> 3. **조치**: `/actuator/**` 전체를 공개한다.
> 4. **왜 안전한가**: 이 경로는 애초에 **네트워크로 막도록** 설계돼 있다 — 외부는 Caddy 가 403, 수집은 SSH 역터널(루프백)로만 들어온다. 익명키 게이트는 사용자 요청을 위한 장치이지 운영 수집 경로를 위한 장치가 아니다.
>
> ⚠️ 이 실패는 **조용하다.** 앱은 멀쩡하고 알림만 멎는다 — 알림이 안 오는 것을 정상으로 착각하기 쉽다.

## D3 — 디버깅 [debug]

> 브라우저에서 API 호출이 전부 CORS 오류로 막힌다. 서버 로그에는 401 만 찍힌다. 원인은?

> [!answer]- 정답 보기
> **preflight(`OPTIONS`)가 인가에서 401 로 끝나기 때문이다.**
> 브라우저는 preflight 에 커스텀 헤더 `X-Anonymous-Key` 를 **싣지 않으므로 원리상 인증될 수 없다.**
> **조치**: `SecurityConfig` 에 `.cors(Customizer.withDefaults())` 를 추가해 `CorsFilter` 를 **인가 앞단**에 둔다.
> ⚠️ **공개 경로를 아무리 열거해도 안 풀린다** — preflight 는 경로가 아니라 **메서드 축**의 문제다.
> `WebConfig` 의 허용 오리진 정책은 그대로 쓰이므로 CORS 정책 자체는 바뀌지 않는다.

## D4 — 설정 [config]

> 새 모듈에 수동 DDL 이 필요하다. 무엇을 만들고, 어떤 실수를 조심하는가?

> [!answer]- 정답 보기
> **만들 것**: `backend/deploy/sql/{module}-v1.sql` — **앱 배포보다 먼저** 적용.
> **조심할 실수 (이 레포가 둘 다 밟았다, 둘 다 로컬에선 안 드러난다)**:
> | 실수 | 증상 |
> |---|---|
> | 테이블명 대문자 | 대소문자 구분하는 리눅스 MySQL 에서만 validate 실패 |
> | `CHAR(64)` | `wrong column type ... expecting varchar(64)` — JPA `String` 은 `VARCHAR` 를 기대 |
>
> **원칙**: 수동 DDL 은 *"논리적으로 맞는 타입"* 이 아니라 **"매핑이 기대하는 타입"** 으로 쓴다.
> **권장**: `AuthModuleBoundaryTest.수동_DDL_이_매핑과_일치한다` 처럼 엔티티 매핑을 읽어 SQL 파일과 대조하는 테스트를 복제한다 — 배포 시점에야 터질 사고를 테스트로 당긴다.

## D5 — 확장 [extend]

> 커버리지 게이트가 실패한다. `coverageExclusions` 에 새 패키지를 추가해 통과시켜도 되는가?

> [!answer]- 정답 보기
> **안 된다. 사유 없는 제외는 커버리지 조작이다.**
> 순서는 이렇다:
> 1. **"왜 테스트할 수 없는가"를 먼저 묻는다.** 대부분은 테스트가 어려운 게 아니라 **설계가 새는 신호**다 — 정적 의존, 시간·랜덤 직접 사용, 과한 방어 코드.
> 2. 도달 불가 라인이면 (a) 테스트로 덮거나 (b) **죽은 코드면 지우거나** (c) 근거를 명시한다.
> 3. 정말 제외가 필요하면 `docs/rule/testing.md` 의 제외 표에 **사유와 함께** 올리고 사람의 판단을 받는다.
>
> 게이트 95%는 **실패선이지 목표가 아니다.** 이번에 손댄 파일은 100%가 기준이다.

---

> [!summary]- 학습 포인트 요약
> | 주제 | 핵심 |
> |---|---|
> | 모듈 경계 | 패키지=모듈. 루트의 public 타입만 공개, `internal/` 은 테스트가 막는다 |
> | `allowedDependencies` | 안 적으면 완전 격리. **적어 넣으면 `verify()` 는 정상으로 본다** |
> | `verify()` 의 한계 | "규칙을 지키는지"는 보지만 **"규칙이 옳은지"는 못 본다** |
> | 이벤트 우선 | `@ApplicationModuleListener` = 커밋 후 + 비동기 + `REQUIRES_NEW`. 소비자 없으면 만들지 않는다 |
> | 순환 해소 | 이벤트로 뒤집거나 **제3의 집계 모듈** |
> | 아웃박스 | 이벤트를 안 써도 `event_publication` 테이블이 필요하다 |
> | 재현 불가한 결함 | H2/MySQL 격리 차이처럼 **재현할 수 없는 차이는 "의존하지 않는 것"으로만** 막는다 |
> | 수동 DDL | 매핑이 기대하는 타입으로. 대소문자·`CHAR`/`VARCHAR` 는 리눅스에서만 터진다 |

## 관련 노트

- [[Modulith 온보딩 맵]] · [[Quick Reference]]
- [[Spring Modulith 아키텍처]] · [[모듈 경계 검증]] · [[모듈 간 통신 — 이벤트 우선]]
- [[auth 모듈]] · [[shared 모듈]] · [[config 모듈]] · [[빌드와 검증 파이프라인]]
- [[스프링-트랜잭션]] — B2·B3 의 이론적 배경
