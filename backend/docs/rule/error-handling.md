# 에러 처리 규칙

> 단일 진실원천. CLAUDE.md·스킬·도메인 문서가 이 문서를 링크한다.

## 구성 요소

| 요소 | 위치 | 역할 |
|------|------|------|
| `ErrorCode` enum | `common/exception/ErrorCode.java` | HttpStatus + 코드 + 메시지 매핑 |
| `BusinessException` | `common/exception/BusinessException.java` | `RuntimeException` + `ErrorCode` |
| `GlobalExceptionHandler` | `common/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` |
| `ErrorResponse` | `common/dto/response/ErrorResponse.java` | `record(code, message)` |

## ErrorCode 네이밍

```
{DOMAIN}_{NNN}     예: COMMON_001, AUTH_002, VOTE_001
```

도메인별 섹션으로 묶고, 도메인 구현 시 해당 섹션에 코드를 추가한다.
각 코드는 `HttpStatus` + 코드 문자열 + 사용자 메시지를 가진다.

현재 등록된 코드:

| 코드 | HttpStatus | 의미 |
|------|-----------|------|
| `COMMON_001` | 400 | 유효하지 않은 입력값 |
| `COMMON_002` | 500 | 서버 내부 오류 |
| `AUTH_001` | 401 | 인증 필요 |
| `AUTH_002` | 401 | 유효하지 않은 토큰 |
| `AUTH_003` | 403 | 접근 권한 없음 (미사용 — 결제 403 은 `PAY_001`/`PAY_007`) |
| `AUTH_004` | 401 | 만료된 access 토큰 (v4 — refresh 유도) |
| `AUTH_005` | 401 | 무효한 refresh — 만료·재사용 (v4 — 재로그인 유도) |
| `PAY_001`~`PAY_007` | 403/409/404/502 | 결제·이용권 — payment.md §7 정본 |

## BusinessException 사용법

```java
throw new BusinessException(ErrorCode.VOTE_001);
```

비즈니스 규칙 위반은 모두 `BusinessException` 으로 던진다.
도메인 서비스는 `ErrorCode` 만 참조하고 HTTP 상태/응답 포맷은 신경 쓰지 않는다.

## GlobalExceptionHandler 처리 범위

| 예외 | 변환 |
|------|------|
| `BusinessException` | `ErrorCode` 의 status + code + message |
| `MethodArgumentNotValidException` | `COMMON_001` (검증 실패) |
| `HttpMessageNotReadableException` | `COMMON_001` (본문 파싱 실패) |
| `AccessDeniedException` | `AUTH_003` |
| `Exception` (최종) | `COMMON_002` |

응답 본문은 항상 `ErrorResponse { code, message }` 형태. REST Docs `common.adoc` 의 공통 에러 규격과 일치한다(→ [rest-docs.md](rest-docs.md)).

## SSR(타임리프) 페이지 예외 — admin 콘솔

`GlobalExceptionHandler` 는 `@RestControllerAdvice` 라 **JSON 전용**이다. SSR 페이지 컨트롤러의 예외는 별도 `@ControllerAdvice` 로 처리한다 (admin v2 §6):

- `AdminPageExceptionHandler` — `basePackages`(`presentation.admin.controller.page`) 한정 + **`@Order(Ordered.HIGHEST_PRECEDENCE)` 필수** (미지정 시 전역 `@RestControllerAdvice` 와 이중 매칭되어 페이지 예외가 JSON 으로 응답됨). 신설 SSR 페이지 컨트롤러는 이 패키지에 배치하면 자동 커버 — 핸들러 등록 불필요(daily-quiz-design.md §4).
- `BusinessException` → referer redirect + flash 에러 메시지, 그 외 → 공용 에러 페이지.
- 보안 체인 내부에서 끝나는 401/403(필터·entryPoint 직접 응답)은 ControllerAdvice 에 도달하지 않는다 — admin 체인의 `exceptionHandling` 구성이 담당.

## 새 도메인 추가 시

1. `ErrorCode` 에 `{DOMAIN}_{NNN}` 섹션을 추가한다.
2. 도메인 서비스에서 `BusinessException(ErrorCode.XXX)` 로 던진다.
3. 컨트롤러 테스트에서 실패 케이스를 REST Docs 로 문서화한다(→ [rest-docs.md](rest-docs.md)).
4. 새 `HttpStatus` 가 필요하면 `GlobalExceptionHandler` 에 핸들러를 추가한다.
