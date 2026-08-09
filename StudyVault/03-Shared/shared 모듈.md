---
module: shared
path: backend/src/main/java/kang20/ytcreator/shared
keywords: open-module, shared-kernel, error-code, security-gate
---

# shared 모듈 (★★★)

#module-shared #pattern-open-module #arch-module-boundary

## 목적

모든 모듈이 공통으로 쓰는 것을 담는 **공유 커널**. `Type.OPEN` 이라 하위 패키지까지 다른 모듈이 참조할 수 있다.

> [!important] 대신 붙는 규칙
> **여기에는 도메인 지식이 없는 것만 둔다.** 특정 도메인 냄새가 나면 그 모듈로 옮긴다.
> 이 규칙이 없으면 `shared` 는 **쓰레기통**이 된다 — OPEN 모듈의 유일한 방어선이 이 규율이다.

```java
@ApplicationModule(displayName = "공유 커널", type = ApplicationModule.Type.OPEN)
package kang20.ytcreator.shared;
```

## 주요 파일

| 파일 | 역할 |
|---|---|
| `shared/domain/BaseTimeEntity.java` | `@CreatedDate`/`@LastModifiedDate` 공통 매핑 |
| `shared/exception/ErrorCode.java` | `{DOMAIN}_{NNN}` enum — HttpStatus + 코드 + 메시지 |
| `shared/exception/BusinessException.java` | `RuntimeException` + `ErrorCode` |
| `shared/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` — JSON 전용 |
| `shared/dto/ErrorResponse.java` | `record(code, message)` |
| `shared/security/AnonymousKeyFilter.java` | 익명키 헤더를 인증 객체로 |
| `shared/security/AnonymousKeyEntryPoint.java` | 401 본문을 **직접** 작성 |
| `shared/security/AnonymousKeyFormat.java` | 형식 검증 + `mask()` |
| `shared/security/AnonymousAuthentication.java` | 인증 토큰 |

## 공개 인터페이스

OPEN 모듈이라 **전부 공개**다. 대신 아래 셋만 다른 모듈이 실제로 쓴다.

| 노출 | 종류 | 설명 |
|---|---|---|
| `BaseTimeEntity` | 추상 클래스 | 엔티티가 상속 |
| `ErrorCode` / `BusinessException` | enum / 예외 | 비즈니스 규칙 위반은 전부 이것으로 |
| `AnonymousKeyFilter.HEADER` | 상수 | `X-Anonymous-Key` — **프론트 계약** |

## 내부 흐름 — 인증 게이트

```text
요청
 │
 ▼
AnonymousKeyFilter                       ⚠️ 어떤 경우에도 거부하지 않는다
 │  헤더 없음      → attribute(MISSING)     ─┐
 │  형식 위반      → attribute(MALFORMED)   ─┤ 전부 체인을 계속 태운다
 │  정상          → SecurityContext 설정    ─┘
 ▼
SecurityConfig 인가 규칙                  (config 모듈)
 │  CORS preflight → CorsFilter 가 먼저 처리
 │  공개 경로      → permitAll
 │  그 외         → authenticated
 ▼ (미인증이면)
AnonymousKeyEntryPoint
    attribute 를 읽어 AUTH_001 / AUTH_002 를 가르고
    ErrorResponse{code,message} 를 401 로 직접 write
```

### 왜 필터가 거부하지 않는가

> [!warning] 공개 엔드포인트에서는 **형식이 틀린 익명키도 무시하고 200** 을 줘야 한다
> 필터가 끊으면 공개 경로까지 막혀 계약이 깨진다.
> → 필터는 **판정하지 않고 사유만 남기고**, 판정은 경로 규칙이 한다.
> 한 필터가 "공개 경로 통과"와 "보호 경로 차단" 두 계약을 **동시에** 만족한다.

### 왜 진입점이 401 본문을 직접 쓰는가

> [!warning] 보안 필터 체인의 401 은 `GlobalExceptionHandler` 에 **도달하지 않는다**
> `@RestControllerAdvice` 는 DispatcherServlet 이후에 동작한다.
> 미인증 요청은 **그 앞에서** 끝나므로, 그냥 두면 Spring Security 기본 401(본문 없음)이 나가고
> 프론트의 `AUTH_001`/`AUTH_002` 분기가 **전부 죽는다.**
> → `AuthenticationEntryPoint` 가 `ErrorResponse` 를 직접 직렬화한다.

## 의존

| 방향 | 모듈 | 경유 |
|---|---|---|
| **사용** | — | 아무것도 참조하지 않는다 (최하위) |
| **사용됨** | `auth`, `config`, 이후 모든 모듈 | OPEN 이라 자유 참조 |

## 설정

| 항목 | 값 | 위치 |
|---|---|---|
| 익명키 헤더명 | `X-Anonymous-Key` | `AnonymousKeyFilter.HEADER` — **한 글자도 바뀌면 안 된다** |
| 에러 응답 형식 | `{ "code", "message" }` | `ErrorResponse` |
| 로그 마스킹 | 앞 4자 + `***` | `AnonymousKeyFormat.mask()` |

## 테스트

```bash
cd backend
./gradlew test --tests "*shared*"
```

| 테스트 | 무엇을 지키나 |
|---|---|
| `AnonymousKeyFilterTest` | 정상/공백/없음/형식위반 — **거부하지 않음**까지 단언 |
| `AnonymousKeyEntryPointTest` | attribute → `AUTH_001`/`AUTH_002` 분기 |
| `AnonymousAuthenticationTest` | `toString()` 은 마스킹, `getPrincipal()` 은 **원문** |
| `SecurityGateTest` | 게이트 통합 — 공개 200 / 보호 401 / preflight |
| `GlobalExceptionHandlerTest` · `BaseTimeEntityTest` | 공용 인프라 |

> [!tip] `AnonymousAuthenticationTest` 의 두 축을 보라
> `toString()` 은 **마스킹**해야 하고 `getPrincipal()` 은 **원문**이어야 한다.
> 뒤바뀌면 각각 다르게 망가진다 — 전자는 로그 유출, 후자는 **서로 다른 사용자가 같은 앞 4자로 뭉쳐
> 남의 계정으로 들어간다.**

## 관련 노트

- [[config 모듈]] — 이 게이트 부품들을 조립하는 곳
- [[auth 모듈]] — `BaseTimeEntity` 를 상속하는 소비자
- [[Spring Modulith 아키텍처]] — OPEN vs CLOSED
- [[Modulith 연습문제]]
