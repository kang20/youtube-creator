# 인증 게이트 — 증상별 원인

> 구조는 [security-chain.md](./security-chain.md). 이 문서는 **막혔을 때 어디를 볼지**만 다룬다.
> 아래는 전부 **이 레포에서 실제로 발생했던** 증상이다.

## 빠른 대조표

| 증상 | 첫 확인 | 원인 |
|---|---|---|
| 새 엔드포인트가 전부 401 | `SecurityConfig.PUBLIC_PATHS` | default-deny 인데 공개 등록을 안 했다 |
| 401 인데 **응답 본문이 비었다** | `exceptionHandling` 진입점 등록 | 등록 누락 → 스프링 기본 401 |
| 브라우저 호출이 **전부 CORS 오류** | `SecurityConfig` 의 `.cors()` | preflight 가 401 로 끝난다 |
| Grafana 알림이 **조용히 멎었다** | `curl /actuator/prometheus` | 스크레이프가 401 |
| 공개 경로인데 형식 위반이 401 | `AnonymousKeyFilter` | 필터가 거부하도록 바뀌었다 |
| 로그에 **익명키 원문**이 보인다 | 아래 §익명키 유출 | 축이 셋이다 |
| 동시 최초 진입에서 500 | `AuthService.register` | 아래 §경쟁 |

---

## 새 엔드포인트가 전부 401

**기본이 인증 필요(default-deny)** 다. 아무것도 하지 않으면 401 이 정상 동작이다.

- 공개로 열어야 하면 `SecurityConfig.PUBLIC_PATHS` 에 추가한다.
- 값의 정본은 [auth.md](../domain/auth.md) §4-2 다 — 코드만 고치고 문서를 안 고치면 다음 사람이 되돌린다.

## 401 인데 응답 본문이 비었다

`SecurityConfig.exceptionHandling(... AnonymousKeyEntryPoint ...)` 등록이 빠졌다.

이 401 은 보안 필터 체인 안에서 끝나 `GlobalExceptionHandler` 에 닿지 않으므로, 진입점이 없으면
**본문 없는 스프링 기본 401** 이 나간다. 그러면 프론트가 `AUTH_001` 과 `AUTH_002` 를 구분할 수 없고
두 분기가 하나로 뭉개진다.

## 브라우저 호출이 전부 CORS 오류 (서버 로그에는 401 만)

**preflight(`OPTIONS`)가 인가에서 401 로 끝나고 있다.**

브라우저는 preflight 에 커스텀 헤더 `X-Anonymous-Key` 를 **싣지 않으므로 원리상 인증될 수 없다.**

- 확인: `SecurityConfig` 에 `.cors(Customizer.withDefaults())` 가 있는가
- ⚠️ **공개 경로를 아무리 열거해도 안 풀린다** — preflight 는 경로가 아니라 **메서드 축**의 문제다
- 허용 오리진 정책은 `WebConfig` 것을 그대로 쓰므로 CORS 정책 자체는 건드리지 않는다

```bash
curl -i -X OPTIONS http://localhost:8080/api/v1/ping \
  -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: POST"
```

## Grafana 알림이 조용히 멎었다

앱은 멀쩡하고 에러도 없는데 알림만 안 온다. **가장 알아채기 어려운 실패**다.

```bash
curl -i http://localhost:8080/actuator/prometheus     # 401 이면 이 문제
```

`PUBLIC_PATHS` 가 `/actuator/health` 만 열고 있으면 스크레이프가 전부 401 이 된다.
→ `/actuator/**` 전체를 연다. 외부 노출은 Caddy 가 403 으로 막고 수집은 루프백으로만 들어오므로
인증 없이 열려도 표면이 늘지 않는다.

## 공개 경로인데 형식 위반이 401

`AnonymousKeyFilter` 가 **거부하도록** 바뀌었다.

필터는 어떤 경우에도 요청을 끊지 않고 사유만 attribute 에 남겨야 한다
([security-chain.md](./security-chain.md) §계약 ①). `AnonymousKeyFilterTest` 가 이걸 단언하므로
정상적으론 여기서 먼저 걸린다.

## 익명키 원문이 로그에 보인다

**축이 셋이고 원인이 다르다.** 어떤 로그인지부터 본다.

| 로그 출처 | 원인 | 조치 |
|---|---|---|
| `o.s.s.w.a.AnonymousAuthenticationFilter` (TRACE) | `AnonymousAuthentication.toString()` 재정의가 지워졌다 | 마스킹 복구 |
| `org.hibernate.orm.jdbc.error` (WARN) | 익명키를 **원문으로 저장**하고 있다 | SHA-256 해시 저장 |
| `o.a.coyote.http11.Http11InputBuffer` (DEBUG) | 루트 로거를 DEBUG 이하로 내렸다 | `org.apache.coyote: INFO` 하한 확인 |

⚠️ **로그는 되돌릴 수 없다** — Loki 14일 + gz 영구 아카이브다. 한 번 들어간 원문은
정책을 고쳐도 지워지지 않는다([logging.md](../ops/logging.md) §3.3).

## 동시 최초 진입에서 500

같은 익명키로 동시에 처음 등록될 때 터진다면 `AuthService.register` 의 트랜잭션 경계를 본다.

| 확인 | 정상 |
|---|---|
| `register` 에 `@Transactional` | **없어야 한다** |
| `UserWriter` 가 별도 빈인가 | 별도 빈 + `REQUIRES_NEW` |
| 호출자가 트랜잭션을 열었는가 | 열면 안 된다 |

⚠️ **H2 에서는 재현되지 않고 운영 MySQL 에서만 터진다** — 격리 수준이 다르기 때문이다.
근거는 [auth-design.md](../domain/auth-design.md) §6-2·§6-4, 회귀 방지는 `AuthTransactionBoundaryTest`.

## 기동조차 안 된다

| 메시지 | 원인 |
|---|---|
| `missing table [event_publication]` | 아웃박스 수동 DDL 미적용. **이벤트를 안 써도 필요하다** |
| `wrong column type ... expecting varchar` | DDL 을 `CHAR` 로 썼다. 매핑이 기대하는 타입은 `VARCHAR` |
| 테이블을 못 찾는다 (리눅스에서만) | 테이블명을 대문자로 만들었다 |

운영은 `ddl-auto: validate` 라 **스키마가 배포로 만들어지지 않는다.**
`backend/deploy/sql/` 이 앱 배포보다 **먼저** 적용돼야 한다.

## 관련 문서

- [security-chain.md](./security-chain.md) — 구조
- [auth-design.md](../domain/auth-design.md) — 설계 근거
- [logging.md](../ops/logging.md) — 로그 보존 정책
