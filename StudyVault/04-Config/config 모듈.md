---
module: config
path: backend/src/main/java/kang20/ytcreator/config
keywords: open-module, security-config, clock-injection, cors
---

# config 모듈 (★★)

#module-config #pattern-open-module #arch-module-boundary

## 목적

전역 스프링 설정 — 보안·CORS·시간·Auditing **빈 정의만** 둔다.

> [!important] 비즈니스 로직 금지
> 여기 도메인 판단이 들어가면 **모든 모듈이 참조하는 곳에 비즈니스가 숨는다.**
> `config` 는 "무엇을 조립하는가"만 알고 "무엇을 결정하는가"는 모른다.

```java
@ApplicationModule(displayName = "전역 설정", type = ApplicationModule.Type.OPEN)
package kang20.ytcreator.config;
```

## 주요 파일

| 파일 | 역할 |
|---|---|
| `config/SecurityConfig.java` | 필터 체인 — default-deny + 공개 경로 + 진입점 + CORS |
| `config/WebConfig.java` | CORS 허용 오리진 (환경변수 주입) |
| `config/TimeConfig.java` | `Clock` 빈 — `Asia/Seoul` |
| `config/JpaAuditingConfig.java` | `@EnableJpaAuditing` |

## 공개 인터페이스

| 노출 | 종류 | 설명 |
|---|---|---|
| `Clock` | 빈 | **시간은 반드시 주입받는다** |
| `SecurityFilterChain` | 빈 | 게이트 조립 결과 |
| `TimeConfig.KST` | 상수 | `ZoneId.of("Asia/Seoul")` |

## 내부 흐름 — `SecurityConfig` 조립

```text
http
 ├─ csrf.disable()              stateless 라 불필요
 ├─ formLogin/httpBasic.disable()
 ├─ sessionCreationPolicy(STATELESS)
 ├─ cors(withDefaults())        ← WebConfig 의 CorsConfigurationSource 를 그대로 쓴다
 ├─ authorizeHttpRequests
 │    ├─ PUBLIC_PATHS("/actuator/**").permitAll()
 │    └─ anyRequest().authenticated()          ← 기본 차단
 ├─ exceptionHandling(AnonymousKeyEntryPoint)  ← 401 본문 계약
 └─ addFilterBefore(AnonymousKeyFilter, UsernamePasswordAuthenticationFilter.class)
```

### 왜 `.cors()` 가 필요한가

> [!warning] preflight 는 **경로가 아니라 메서드 축**의 문제다
> 브라우저는 preflight(`OPTIONS`)에 커스텀 헤더 `X-Anonymous-Key` 를 **싣지 않는다.**
> 원리상 인증될 수 없으므로 default-deny 하에서는 **전부 401** 이 되고,
> 브라우저는 preflight 실패를 곧 CORS 실패로 처리해 **크로스 오리진 호출이 전부 막힌다.**
>
> 공개 **경로**를 아무리 열거해도 안 풀린다 → Security 의 `CorsFilter` 를 **인가 앞단**에 둔다.

### 왜 `/actuator/**` 전체를 여는가

> [!tip] 이 경로는 애초에 **네트워크로 막도록** 설계돼 있다
> 외부는 Caddy 가 403 으로 차단하고, 수집은 모니터링 서버의 SSH 역터널(루프백)로만 들어온다.
> 익명키 게이트는 **사용자 요청을 위한 장치이지 운영 수집 경로를 위한 장치가 아니다.**
>
> `/actuator/health` 만 열면 **Prometheus 스크레이프가 401 이 되어 Grafana 알림이 조용히 멎는다.**

## 의존

| 방향 | 모듈 | 경유 |
|---|---|---|
| **사용** | `shared` | `AnonymousKeyFilter`, `AnonymousKeyEntryPoint` |
| **사용됨** | — | 설정은 참조당하지 않는다 |
| **절대 안 함** | `auth` 등 도메인 | ⛔ 게이트 부품을 `shared` 에 둔 이유가 이것 |

```text
✅ config → shared      게이트는 공통 장치
❌ config → auth        설정이 도메인을 참조하게 된다
```

## 설정

| 환경변수 / 키 | 용도 | 기본값 |
|---|---|---|
| `ytcreator.cors.allowed-origins` | CORS 허용 오리진 | `http://localhost:5173` |
| `logging.level.org.apache.coyote` | **내리지 마라** — Tomcat 이 DEBUG 에서 수신 헤더를 덤프한다 | `INFO` (하한 고정) |
| `JPA_DDL_AUTO` | 운영 스키마 정책 | `validate` |

> [!warning] `Clock` 빈이 있어도 `createdAt` 은 고정되지 않는다
> `BaseTimeEntity.createdAt` 은 JPA Auditing 이 채우고, 그 시간 제공자는 `TimeConfig` 의 `Clock` 을
> **보지 않는다.** 테스트에서 `Clock` 을 고정해도 `createdAt` 은 안 고정된다.
> → 절대값 대신 **상대 비교**로 검증한다.

## 테스트

- `config/**` 는 **커버리지 제외** 대상이다 (빈 정의뿐 — 통합 테스트가 간접 검증).
- ⚠️ **제외라도 동작은 반드시 검증한다** — 게이트는 계약이다. `SecurityGateTest` 가 실제 요청으로 확인한다.

```bash
cd backend
./gradlew test --tests "*SecurityGateTest"
```

> [!tip] 엔드포인트가 없는데 게이트를 어떻게 테스트하나
> `src/test` 안에 **테스트 전용 프로브 라우트**(`RouterFunction`)를 둔다.
> 운영 코드를 더럽히지 않고 실제 요청으로 게이트를 검증할 수 있다 — 이 레포가 찾은 패턴이다.

## 관련 노트

- [[shared 모듈]] — 조립되는 부품들이 사는 곳
- [[auth 모듈]] — config 가 참조하지 **않는** 모듈
- [[Spring Modulith 아키텍처]] — 의존 방향 결정
- [[빌드와 검증 파이프라인]]
- [[Modulith 연습문제]]
