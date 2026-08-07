# 로깅 전략·시스템 설계

> ✅ **결정 확정(2026-07-08, 사용자)**: 수집 = (a) Loki+Alloy · Caddy 액세스 로그 **off** → 조합 제약에 따라 봇 404 는 **WARN** · MDC 식별 = anonHash · Loki hot 14일 + 매일 gz 아카이브(영구) · DB VM 로그 제외. §8 의 해당 🔶 는 모두 확정됨.

> 인프라 전제: [deploy.md](../deploy.md) · [monitoring.md](monitoring.md) — **터널 유닛 정본은 monitoring.md §5.3** (이 문서는 참조만)
> 규칙: [error-handling.md](../rule/error-handling.md) · [architecture.md](../rule/architecture.md) · [testing.md](../rule/testing.md)
> ⚠️ **MDC 키 계약의 정본은 이 문서 §3.3** — admin 설계([domain/admin.md](../domain/admin.md)) 등 다른 문서는 여기 정의를 참조한다.

## 1. 목적과 범위

메트릭만 있고 로그는 컨테이너 stdout뿐인 현 상태에서 **(1) 레벨 정책, (2) 디스크 안전판, (3) 수집·조회 체계**를 비용 0원 제약 안에서 단계 도입한다.

**Phase 0~1 (즉시)**: 레벨 정책 · 봇 스캔 404 노이즈 제거 · MDC `requestId` · docker 로그 로테이션
**의도적 제외 (백로그)**: 분산 트레이싱(모놀리스라 requestId로 충분) · 감사 로그 DB 적재(→ admin 설계 §7) · 로그 기반 알림(→ monitoring.md의 **Grafana Alerting** 위에 Loki 쿼리 룰로, Loki 도입 후)

## 2. 현황 진단 (실측)

| 항목 | 현재 | 문제 |
|---|---|---|
| 앱 로깅 코드 | `GlobalExceptionHandler` 단 한 곳 | 정상 요청·필터 거부는 기록 0 |
| 봇 스캔 404 | 매핑 없는 경로가 catch-all(`Exception.class`)로 | **ERROR+스택트레이스+응답 500(COMMON_002)** — 레벨·상태코드 둘 다 버그 |
| `AnonymousKeyFilter.writeError` | 400 직접 응답, 로그 없음 | 필터 거부 = 관측 사각지대 |
| logging 설정 | 없음 (yml `logging:` 0, logback xml 0) | 백지 — 충돌 없이 신설 가능 |
| 컨테이너 로그 | compose 3종 전부 `logging:` 옵션 없음 | json-file **무제한 누적** — 디스크 고갈 리스크 |
| Caddy | `log` 지시자 없음 | HTTP 액세스 로그 부재 |
| 수집·보관 | 없음 | 컨테이너 재생성(=매 배포) 시 로그 소실 |

## 3. 애플리케이션 로깅 전략

### 3.1 레벨 정책 — "ERROR = 사람이 조치"

| 레벨 | 기준 | 예 |
|---|---|---|
| ERROR | 사람이 보고 조치. 알림 트리거 대상 | 500, DB 커넥션 고갈, Object Storage 실패 |
| WARN | 비정상이나 시스템이 처리 — 반복되면 확인 | `BusinessException`(4xx), 검증 실패, 익명키 형식 위반, push 청크 실패(QuizPush — best-effort 스킵) |
| INFO | 정상 라이프사이클·운영 이벤트 | 기동/종료, AdminAudit(→ admin §7), QuizPush 발송(→ daily-quiz-design §6-4) |
| DEBUG | 개발 진단, 운영 off | 봇 404(🔶), SQL |

- `application-prod.yml`에 `logging.level.root: INFO` 명시. 목표: **ERROR 0건인 날이 정상**인 상태.

### 3.2 봇 스캔 404 제거 (Phase 0 — 다른 무엇보다 선행)

> monitoring.md HighErrorRate 알림의 선행 조건이기도 하다. **🔶 게이트와 무관하게 단독 배포 가능** — 이 항목만은 확정 없이 진행한다(상태코드 500→404는 명백한 버그 수정).

수술 지점: `GlobalExceptionHandler`에 전용 핸들러 추가 — @ExceptionHandler는 선언 순서가 아니라 **타입 특이성**으로 우선 매칭되므로 파일 내 위치는 무관.

```java
@ExceptionHandler(NoResourceFoundException.class)
public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    log.debug("[NoResourceFound] path={}", e.getResourcePath()); // 🔶 WARN 선택 시 교체 — §8 조합 제약 참조
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND));
}
```

- `ErrorCode.RESOURCE_NOT_FOUND` = `COMMON_004`(404) 신설 → **api-spec.md 공통 에러 선반영**(계약 변경 규칙) + REST Docs 404 스니펫.
- 함께: `HttpRequestMethodNotSupportedException` → 405 `COMMON_005`(WARN), `AnonymousKeyFilter.writeError`에 WARN 1줄(사각지대 해소).

### 3.3 MDC 계약 (정본) — `requestId` + 식별 컨텍스트

**MDC 키 계약** (모든 설계 문서가 이 표를 참조):

| 키 | 값 | 넣는 곳 | 텍스트 로그 노출 | JSON(Phase 3) |
|---|---|---|---|---|
| `requestId` | 🔶 UUID 앞 8자(추천) vs 풀 UUID | `MdcLoggingFilter` | ✅ 패턴에 포함 | ✅ 자동 |
| `anonHash` | 🔶 `SHA-256(익명키)` 앞 8자 (원문 절대 금지) | `MdcLoggingFilter` | ❌ (폭 절약) | ✅ |
| `adminName` | 관리자 **이름** (관리자 인증 시) | `AdminAuthFilter` (admin v2 §9) | ✅ 패턴에 포함 — "admin 이름 무조건 로그 출력" 요구(2026-07-10 확정) | ✅ |

> ⚠️ 구 키 `admin`(`"true"` 불리언, `AdminTokenFilter` 주입 예정)은 **admin v2 재설계(2026-07-10)로 폐기** — 단일 공유 토큰 전제라 "누가"를 식별할 수 없었다. 텍스트 감사 추적은 `[AdminAccess]`(인증 요청당 1줄) + `[AdminAudit]`(상태 변경, 이름 포함)가 담당한다.

> `[QuizPush]` **오늘의 문제 push 발송 로그** — 발송 결과는 콘솔에 표시하지 않으므로 **서버 로그가 유일한 관측점**. 계약 정본은 [daily-quiz-design.md §6-4](../domain/daily-quiz-design.md): `start`(INFO, 요청 스레드 — MDC 유지) / `chunk`(INFO) / `chunk-failed`(WARN, 청크 스킵 후 계속 — **예외 클래스명만 요약, 응답 body·스택 미첨부**) / `partial-fail`(WARN — 철회·무효 키 관찰 근거 수집처) / `done`(INFO, 종료 요약) / `dispatch-failed`(WARN, 최외곽 방어선) 6종. 어댑터는 `enabled=false` 일 때 `disabled`(INFO, 전송 생략) 1줄을 추가로 남긴다(발송 비활성 구간 관측용). 발송 루프는 가상 스레드라 MDC 미전파 — 모든 라인에 `quizId` 를 직접 포함해 상관관계를 확보한다. **로그 금지: anonKey 원문·요청/응답 body 원문·인증서 경로/비밀번호**(같은 문서 §6-5).

**등록 방식 — 서블릿 레벨 (모든 체인 공통 통과 보장)**: `/admin/**`은 별도 SecurityFilterChain이라(admin §5.1) Security 체인 내부에 등록하면 admin 요청이 MDC를 못 받는다. 따라서 `MdcLoggingFilter`는 **`FilterRegistrationBean`으로 서블릿 레벨, `Ordered.HIGHEST_PRECEDENCE`** 등록 — springSecurityFilterChain(-100)보다 앞서 모든 요청에 적용되고, `finally { MDC.clear(); }`로 정리한다. 위치: `common/logging/` 신설(도메인 의존 없음 — ArchUnit 무충돌).

- 🔶 응답 헤더 `X-Request-Id` 에코 — 프론트 문의 대응에 유용(추천 on, api-spec.md 반영 필요).
- 패턴은 logback xml 없이 yml 한 줄 (admin v2에서 `adminName` 노출 확장):

```yaml
logging:
  pattern:
    level: "%5p [%X{requestId:-}]%replace( admin=%X{adminName}){' admin=$', ''}"
```

> `%replace` 는 비admin 요청에서 ` admin=` 잔여 문자열을 제거한다. 문법이 불안정하면 고정 슬롯 `[%X{adminName:-}]` 로 대체(admin v2 §9).

### 3.4 구조화 로깅(JSON) — Phase 3 유보

수집 없는 지금 JSON은 가독성만 해침. **Loki 채택 시** Spring Boot 내장 구조화 로깅(`logging.structured.format.console: ecs` 🔶)으로 의존성 0 도입 — §3.3의 MDC 키가 JSON 필드로 자동 포함된다. ⚠️ `requestId`·`anonHash`는 **Loki 라벨 금지**(고카디널리티 → 스트림 폭발) — `| json` 파이프라인 필터 전용. 라벨은 job/service/env 수준만.

## 4. 로그 수집 시스템 — 후보 비교

### (a) Loki(Pi) + Alloy(앱 VM), 리버스 터널(-R) 푸시

> promtail은 deprecated(EOL 2026-03) — 신규는 **Grafana Alloy**.

```
[앱 VM] Alloy(host 네트워크, docker 로그 수집) → 127.0.0.1:3100
            ▲ sshd -R 포워드 (GatewayPorts 기본값 no → 루프백 바인딩. 그래서 Alloy 는 network_mode: host 필수)
[Pi] ytcreator-tunnel.service 에 -R 127.0.0.1:3100:127.0.0.1:3100 1줄 추가 (정본: monitoring.md §5.3 — 그 문서 블록으로만 수정)
     Loki(127.0.0.1:3100, filesystem, retention 🔶) ← Grafana 데이터소스
```

- 전제 충족 확인됨: Pi 키가 앱 VM에 등록돼 있어 `-R` 가능, **집 공유기 포트 추가 개방 불필요**.
- `-R` 실패 모드: `ExitOnForwardFailure=yes`(기적용)가 바인드 실패 시 프로세스를 죽여 systemd가 재시작 — stale 리스너로 인한 **조용한 로그 push 사망은 방지**되나, 대신 재시작 루프 가능(수용 — monitoring.md §5.3 참조).
- **유실 내성 한계 (정직한 평가)**: 집 회선/Pi는 가정용 SPOF. Alloy 재시도 버퍼는 기본 인메모리·유한이라 수 시간~수일 단절 시 초과분 유실 + §5.1 로테이션(50MB 상한)과 겹치면 회전으로 밀려난 로그는 영구 소실. 채택 시 **Alloy WAL(디스크 버퍼) 활성화 필수**, 로테이션 상한↔허용 단절 시간 관계 산정.
- **노출면 증가**: 인터넷 포트포워딩된 평문 HTTP Grafana 뒤에 클라 IP·anonHash 로그가 놓임 → **monitoring.md §9(Grafana 접근 전환) 선행을 Phase 2 전제 조건으로**. (2026-07-15: §9 는 Tailscale 채택·tailnet 구축 완료 — 잔여는 공유기 포워딩 폐쇄, tailscale-migration-plan.md Phase 3)
- DB VM 로그는 범위 제외 추천 🔶(편입 시 GatewayPorts+보안목록 3100 vs Pi→DB 점프 터널 중 택일 — 가치 대비 비용 큼).
- 착수 조건: **Pi 실측 선행**(`free -h`/`df -h`/현 컨테이너 사용량) + Loki `mem_limit`로 Prometheus/Grafana 보호.

### (b) Grafana Cloud 무료 티어

로그 50GB/월·보존 14d — 현 규모 충분. VM→Cloud 직접 push라 Pi·터널 변경 0, **가용성·내구성은 (a)보다 우위**(SaaS). 단점: 로그(IP 포함)가 외부로, API 토큰 시크릿 추가, 대시보드 이원화 우려.

### (c) 수집 없음 + 로테이션 (현상 유지+)

§5 안전판만. 매 배포마다 과거 로그 소실 — 장애 사후 분석 불가.

### 비교·추천

| | (a) Loki+Alloy | (b) Grafana Cloud | (c) 유지+ |
|---|---|---|---|
| 비용 | 0원 | 0원(무료 티어) | 0원 |
| 운영 부담 | 중 | 하 | 없음 |
| **가용성/유실 내성** | **집 인프라 의존(열세)** — WAL로 완화 | SaaS(우위) | — |
| 데이터 주권 | 집 안 | 외부 | VM 내 |
| Grafana 통합 | 자연 통합 | 이원화 우려 | 불가 |

🔶 **추천: (a)** — 기존 자가 운영 스택·터널 인프라의 한계비용이 가장 낮고 "공인 포트 미개방" 원칙과 일관. 단 유실 내성·노출면의 열세를 위 완화책과 함께 수용하는 결정임을 명시. 부담 싫으면 (b)도 합리적. (c)는 어느 경우든 Phase 1로 선행.

## 5. 당장의 안전판 (Phase 1 — 수집 결정과 무관)

### 5.1 docker json-file 로테이션

YAML 앵커는 **통째 참조라 부분 오버라이드 불가** — 용도별 2개로 나눈다:

```yaml
x-logging: &app-logging        # 앱·mysql 등 로그 많은 것
  driver: json-file
  options: { max-size: "10m", max-file: "5" }
x-logging-small: &small-logging  # caddy·exporter류
  driver: json-file
  options: { max-size: "10m", max-file: "3" }

services:
  app:
    logging: *app-logging
```

- 대상: **세 compose의 모든 서비스 + 이후 추가되는 모든 신규 서비스**(monitoring.md의 node-exporter·blackbox, 본 문서의 alloy 포함 — 나열이 아니라 규칙).
- ⚠️ **Pi 적용 단계 필수**: `monitoring/docker-compose.yml`은 CI 대상이 아님 — 수정 후 Pi로 scp + `docker compose up -d`(재생성) 해야 반영(`/pi-ops`). 앱/DB VM은 배포 워크플로가 재생성.

### 5.2 Caddy 액세스 로그 🔶

`Caddyfile` 사이트 블록에 `log` 지시자 — stdout JSON으로 `docker logs`(→ 추후 Loki)에 실림. 추천 **on**(봇 스캔·상태코드 분포의 관측 창구). 반대급부: 클라 IP 저장 — 보존 정책(로테이션이 상한) 인지.

## 6. Grafana 로그 UX (Loki 채택 시)

- 데이터소스 provisioning에 Loki 추가(기존 패턴). Explore: `{job="ytcreator-app"} |= "ERROR"` → 라인에서 requestId 확인 → `|= "<requestId>"`로 요청 전체 타임라인 복원. 대시보드 옆 split view로 메트릭 스파이크 ↔ 로그 상관.
- derived field·전용 Logs 대시보드는 **운영 중 불편이 실증되면** 후속(요청당 1인 소비자 규모에 선구축은 과함). 도입 시 정규식은 실제 포맷 기준 — 텍스트 패턴이면 `\[([0-9a-f]{8})\]`, ECS면 `"requestId":"([0-9a-f]+)"` (🔶 requestId 길이 확정과 연동).

## 7. 로드맵

### Phase 0 — 단독 선행 (🔶 게이트 없음)
- [ ] `GlobalExceptionHandler` 404/405 핸들러 + `ErrorCode` COMMON_004/005 + api-spec.md 선반영 + REST Docs 스니펫 → 배포 (monitoring.md HighErrorRate의 선행 조건)

### Phase 1 — 레벨·MDC·로테이션
- [ ] `AnonymousKeyFilter.writeError` WARN 1줄
- [ ] `common/logging/MdcLoggingFilter` — **FilterRegistrationBean 서블릿 등록(HIGHEST_PRECEDENCE)** + `logging.pattern.level` + `logging.level.root: INFO`(prod)
- [ ] 컨트롤러/필터 테스트 + ArchUnit 확인
- [ ] compose 3종 로테이션 앵커(§5.1) + **Pi scp·재생성 적용**
- [ ] 🔶 Caddy 액세스 로그(§5.2)
- [ ] `deploy.md` 로그 확인 절차 갱신

### Phase 2 — 수집 (🔶 (a) 확정 시)
- [ ] 선행: monitoring.md §9 Grafana 접근 전환 + Pi 리소스 실측
- [ ] Pi: compose에 `loki`(127.0.0.1:3100, mem_limit, filesystem, retention 🔶 14d/30d) + config 신설 + 데이터소스 provisioning
- [ ] Pi: 터널 유닛에 `-R` 추가 — **monitoring.md §5.3 정본 블록으로 수정** + 재부팅·강제재시작 후 재바인드 검증
- [ ] 앱 VM: compose에 `alloy`(network_mode: host, docker.sock·containers 마운트, **WAL 활성화**) + `deploy/alloy/config.alloy` 신설 + 워크플로 scp source 확장
- [ ] README·구성도 갱신

### Phase 3 — JSON 구조화
- [ ] prod만 `logging.structured.format.console: ecs` 🔶 + Alloy JSON 파싱 + 쿼리 정비
- [ ] 로그 기반 알림(Grafana Alerting + Loki 룰) · tracing 재평가(계속 백로그 추천)

## 8. 결정 필요 (Open Questions)

**확정 제안**: 404 응답 교정(500→404)은 버그 수정이라 즉시 · 로테이션 값 10m×5/3 · JSON은 수집 후.

**미결 🔶 (조합 제약 포함)**:

| 항목 | 선택지 | 제약 |
|---|---|---|
| 수집 시스템 | (a) Loki+Alloy(추천) / (b) Grafana Cloud / (c) 유지+ | (a)는 monitoring §9 선행 + Pi 실측 |
| 봇 404 레벨 | DEBUG(추천) / WARN | **Caddy 로그 off를 고르면 DEBUG 금지(WARN 필수)** — 관측 창구 0 방지 |
| Caddy 액세스 로그 | on(추천) / off | 위 조합 제약 |
| MDC 식별 | anonHash(추천) / userId PK / 병행 · requestId 8자(추천)/풀 · X-Request-Id 에코 여부 | 에코 on이면 api-spec 반영 |
| Loki 보존 | 14d / 30d | Pi 디스크 실측 후 |
| DB VM 로그 | 제외(추천) / 편입 | 편입 시 별도 네트워크 작업 |
