> ⚠️ **이 문서는 v3 이전(익명키 필터 체인) 기준이다 — auth v4(JWT 전환, 2026-08-12)로 대체됐다.**
> 현행 정본: [auth.md](../domain/auth.md) §4-2 · [auth-design.md](../domain/auth-design.md) §14.
> `AnonymousKeyFilter`·`AnonymousAuthentication`·`AnonymousKeyEntryPoint` 는 삭제됐고
> `JwtAuthenticationFilter`·`UserAuthentication`·`TokenAuthenticationEntryPoint`(auth 모듈 루트)가 대체한다.

# 익명키 인증 게이트 — 구조

> **범위**: 요청이 어떻게 인증되고 어디서 막히는가. **결정의 근거는 여기 없다** —
> "왜 이렇게 정했나"는 [auth-design.md](../domain/auth-design.md), 요구·계약은 [auth.md](../domain/auth.md) 가 정본이다.
> 이 문서는 **지금 코드가 어떻게 생겼는지**만 다룬다.

## 부품이 두 곳에 흩어져 있다

파일을 하나씩 읽으면 체인 전체가 안 보인다. 조립과 부품이 다른 모듈에 있기 때문이다.

| 위치 | 무엇 |
|---|---|
| `config/SecurityConfig` | **조립** — 체인 구성, 공개 경로 목록, 부품 배치 |
| `shared/security/**` | **부품** — 필터·인증 토큰·진입점·형식 규칙 |

`auth` 모듈에는 게이트가 **없다.** 게이트는 전 도메인 공통 장치이고, `auth` 에 두면
`config → auth` 의존이 생겨 설정이 도메인을 참조하게 된다([auth-design.md](../domain/auth-design.md) §2-1 쟁점 3).

## 필터 체인에서의 위치

```
요청
 │
 ├─ CorsFilter                      preflight(OPTIONS)를 여기서 끝낸다 — 인가 이전
 │
 ├─ AnonymousKeyFilter          ★ 헤더 → 인증 객체
 │      거부하지 않는다. 사유만 request attribute 에 남긴다
 │
 ├─ AnonymousAuthenticationFilter   스프링 기본 장치 — 우리 것이 아니다(§이름 혼동)
 │
 ├─ ExceptionTranslationFilter      미인증이면 진입점을 부른다
 │      └─▶ AnonymousKeyEntryPoint  ★ 401 본문을 직접 쓴다
 │
 └─ AuthorizationFilter             permitAll / authenticated 판정
```

★ = 우리 부품

> **`addFilterBefore` 의 기준점이 체인에 없다**
> `SecurityConfig` 는 `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` 로 위치를 잡는데,
> `formLogin` 을 껐으므로 그 필터는 **체인에 존재하지 않는다.** 버그처럼 보이지만 이 인자는
> **순서 기준점(anchor)** 으로만 쓰이므로 정상 동작한다. 고치지 마라.

## 부품과 책임

| 타입 | 책임 |
|---|---|
| `AnonymousKeyFilter` | 헤더를 읽어 인증 객체를 만들거나, 거부 사유를 attribute 에 남긴다 |
| `AnonymousAuthentication` | 인증 토큰. `getPrincipal()` 이 익명키 **원문** |
| `AnonymousKeyEntryPoint` | attribute 를 읽어 `AUTH_001`/`AUTH_002` 를 가르고 401 본문을 쓴다 |
| `AnonymousKeyFormat` | 입력 형식 규칙(`isValid`)과 로그 마스킹(`mask`) |

## 판정 표

| 헤더 상태 | 필터가 하는 일 | 공개 경로 | 인증 필요 경로 |
|---|---|---|---|
| 없음 / 공백 | attribute = `MISSING` | 200 | **401 `AUTH_001`** |
| 형식 위반 | attribute = `MALFORMED` | 200 | **401 `AUTH_002`** |
| 정상 | 인증 객체 설정 | 200 | 200 |

- 공개 경로에서는 attribute 가 쓰이지 않고 버려진다.
- 공개 경로 목록은 `SecurityConfig.PUBLIC_PATHS` 에 있다. 현재 `/actuator/**` 하나뿐이다.
- 401 응답 본문은 `{ "code", "message" }` — [error-handling.md](../rule/error-handling.md) 규격 그대로다.

## 깨뜨리면 안 되는 계약 셋

이 셋은 전부 **실제로 한 번씩 깨졌던** 것이다.

### ① 필터는 요청을 거부하지 않는다

공개 경로는 **형식이 틀린 익명키도 무시하고 200** 을 줘야 한다. 필터가 형식 위반을 그 자리에서
401 로 끊으면 공개 경로까지 막혀 계약이 깨진다.

→ 필터는 **판정하지 않고 사유만 남기고**, 판정은 인가 규칙이 한다. 한 필터가 두 계약을 동시에 만족한다.

### ② 진입점이 401 본문을 직접 쓴다

이 401 은 **보안 필터 체인 안에서 끝나** `GlobalExceptionHandler`(`@RestControllerAdvice`)에 도달하지 않는다.
진입점이 없으면 **본문 없는 스프링 기본 401** 이 나가고, 프론트의 `AUTH_001`(SDK 재호출) /
`AUTH_002`(안내 후 종료) 분기가 **전부 죽는다.**

### ③ 익명키 원문을 남기지 않는다

로그·예외 메시지·응답 본문 어디에도. 진단이 필요하면 `AnonymousKeyFormat.mask()` 를 거친다.

**"우리 코드가 안 찍어도 프레임워크가 찍는다"** 가 이 도메인에서 세 번 반복됐다:

| 축 | 누가 찍나 | 막는 방법 |
|---|---|---|
| 문자열 표현 | 스프링이 인증 객체를 TRACE 로 | `AnonymousAuthentication.toString()` 마스킹 |
| DB 제약 위반 | Hibernate 가 위반 값을 WARN 으로 | 저장을 SHA-256 해시로 |
| 수신 헤더 | Tomcat 이 DEBUG 에서 요청 덤프 | `logging.level.org.apache.coyote: INFO` 하한 |

## ⚠️ 이름 혼동

| 이름 | 정체 |
|---|---|
| `AnonymousAuthentication` | **우리 것** — 익명키 인증 토큰 |
| `AnonymousAuthenticationFilter` | **스프링 것** — 익명키와 무관. 미인증 요청에 "익명 사용자" 토큰을 채우는 기본 장치 |

그리고 **이 스프링 필터가 인증된 요청마다 토큰을 TRACE 로 찍는 주체**다
(`Did not set SecurityContextHolder since already authenticated ...`).
`toString()` 마스킹이 필요한 이유가 바로 이것이다.

## 여기 없는 것

| 없는 것 | 어디에 |
|---|---|
| **로그인 경로** | 없다. 이 서비스는 토스 로그인을 쓰지 않는다 — 전 구간 익명키 단일 식별 |
| 인가(권한) 판정 | 각 도메인 모듈. "구독이 없어 못 쓴다"는 `subscription` 몫이고 게이트는 "누구인가"까지만 답한다 |
| 공개 경로 목록 | `config/SecurityConfig.PUBLIC_PATHS` |
| 익명키 진위 검증 | **하지 않는다.** 토스 verify API 는 기각됐다([auth.md](../domain/auth.md) §4-3) |

## 관련 문서

- [auth.md](../domain/auth.md) — 요구·API 계약 (정본)
- [auth-design.md](../domain/auth-design.md) — 설계 근거
- [troubleshooting.md](./troubleshooting.md) — 증상 → 원인
- [error-handling.md](../rule/error-handling.md) · [logging.md](../ops/logging.md)
