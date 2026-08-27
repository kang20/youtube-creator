# CLAUDE.md — ytcreator 백엔드

> 경량 허브. 규칙 본문은 `docs/rule/*.md` 에 있고 여기서는 링크만 건다.

## 기술 스택

Spring Boot 4 · **Spring Modulith** · Java 25 · Gradle(Kotlin DSL) · JPA · Spring Security(stateless 익명키)
· Spring REST Docs · H2(test)/MySQL(운영)

## 핵심 원칙 (한 줄 요약 + 상세 링크)

- **아키텍처**: Spring Modulith — 패키지=모듈, 경계는 테스트가 강제 → [docs/rule/architecture.md](docs/rule/architecture.md)
- **테스트**: 커버리지 목표 100%(게이트 수치는 testing.md "커버리지" 가 정본), 컨트롤러 테스트 = REST Docs → [docs/rule/testing.md](docs/rule/testing.md)
- **API 문서**: Spring REST Docs, HTML 만 main push → [docs/rule/rest-docs.md](docs/rule/rest-docs.md)
- **에러 처리**: ErrorCode enum + BusinessException → [docs/rule/error-handling.md](docs/rule/error-handling.md)
- **인증 게이트**: JWT Bearer (v4) — 부품은 `auth` 모듈 루트, 조립은 `config`. 익명키는 부트스트랩(로그인) 전용 → [docs/domain/auth.md](docs/domain/auth.md) §4-2 · auth-design §14
- **권한**: `USER`/`ADMIN` 둘뿐 (v5). 판정 근거는 JWT `role` 클레임(DB 조회 없음), `ADMIN ⊃ USER` 는 `RoleHierarchy`, 운영자 경로는 `/api/v1/admin/**`. 승격 API 없음 — DB 직접 변경 → auth.md §4-8 · auth-design §15
- **배포/인프라**: OCI VM 2대 + 블루-그린 → [deploy/](deploy/) (스크립트·compose·Caddy), 스킬 `/infra`
- **모니터링**: 기존 모니터링 서버에 얹는다 → [monitoring/onboarding/README.md](monitoring/onboarding/README.md)

## 모듈 목록

<!-- TODO: 모듈이 생길 때마다 한 줄씩. `./gradlew test --tests "*ModularityTest"` 로 실제 구조 확인 -->

| 모듈 | 책임 | 허용 의존 |
|---|---|---|
| `shared` | 공용 예외·에러코드·감사시각·타입 ID 공통 부모·`@Support` (OPEN) | — |
| `config` | 전역 스프링 설정(보안 조립·CORS·Clock·Auditing) (OPEN) | `shared`, `auth` |
| `auth` | 익명키 로그인·JWT 발급/게이트/갱신. 노출: `AuthPort`·`UserId`·`Role`·게이트 부품 | `shared` |
| `payment` | 결제·지급 원장. 노출: `ConsumableGranted`·`SubscriptionGranted`·`OrderId`(+컨버터)·`PaymentUsagePort`(구현체는 이연 — 전부 거부 임시 어댑터) | `shared`, `auth` |
| `credit` | 횟수권 잔량. **노출 없음** — `ConsumableGranted` 구독 | `shared`, `auth`, `payment` |
| `subscription` | 구독 계약·웹훅. **노출 없음** — `SubscriptionGranted` 구독 | `shared`, `auth`, `payment` |
| `subtitle` | 자막 작업(Job) — 업로드→대본→확정→자막 파일. **노출 없음** | `shared`, `auth`, `payment` |
| `bootstrap` | 진입 집계(로그인) — 저장소 없음 | `shared`, `auth` |

- **노출 = 다른 모듈이 실제로 import 하는 것**이다. 자기 컨트롤러·리스너만 부르는 포트는
  `{module}/internal/port/` 에 둔다 — `ArchitectureConventionTest` R1 이 강제한다.

## 도메인 문서 위치

**도메인 하나 = 폴더 하나다.** 그 도메인에 관한 산출물이 전부 그 안에 모인다.

```
docs/new-domain/
├── {name}/                         도메인 하나
│   ├── {name}-v{n}.md              정의서 — 요구·참고자료·용어 사전·도메인 모델이 한 파일에
│   ├── {주제}-{YYYY-MM-DD}.html    ADR — 그 도메인의 의사결정 기록 (`/adr-create`)
│   └── {주제}.md                   다이어그램 — Mermaid (`/diagram`)
└── {a}-{b}/                        도메인 **사이**의 규약·관계 문서. 폴더명은 도메인명 알파벳순
```

- 정의서는 **한 파일**이다. 요구·참고자료·용어 사전·도메인 모델이 그 안에 순서대로 쌓인다 —
  요구서와 설계서를 따로 두지 않는다. 요구가 바뀔 때 모델이 같이 눈에 들어와야 한다.
- **폴더명과 정의서 파일명 앞부분이 같아야 한다**(`subtitle/subtitle-v1.md`). 어긋나면 검증이 FAIL 이다.
- 버전이 오르면 `git mv` 로 **파일만 rename** 한다. 폴더는 그대로다. 이전 버전 파일을 남기지 않고,
  걸린 링크는 함께 고친다.
- **도메인 간 문서를 어느 한쪽 폴더에 넣지 않는다** — 한쪽에 넣으면 반대쪽에서 찾지 못한다.
  `payment-subtitle/` 처럼 **알파벳순 하이픈**으로 폴더를 따로 만든다. 순서를 고정해야
  같은 조합이 두 폴더로 갈리지 않는다.
- `docs/domain/` 의 5개(`auth.md` · `auth-design.md` · `payment.md` · `payment-design.md` · `subtitle.md`)는
  **구 양식 레거시(읽기 전용)** 다. 그 도메인은 코드가 정본이므로 선례 참조로만 읽고 재작성하지 않는다.
- 🔶 는 미확정 표시다. `## 🔶 결정 필요` 에 모여 있고, **0건이 되기 전에는 다음 단계로 넘어가지 않는다.**

## 토스 인앱 MCP 활용

apps-in-toss MCP 로 플랫폼 문서를 조회한다. 검색은 **한국어 키워드 필수**.

- 검색 `search_docs` → 상세 `get_doc`. 구현 전 반드시 최신 스펙 확인 → `docs/platform/` 대조
- 자동화: `/toss-api`

## 개발 흐름

- 코드 작업은 **backend 브랜치**. push = 배포 트리거.
- 문서(`docs/api/` HTML, 기획 문서)는 **`/docs-sync` 스킬로만** main 에 push.
- 커밋은 역할별 분리: feat / test / refactor / fix / docs / chore (혼합 금지).
- 새 도메인:

  ```
  /usecase {name}              요구      ┐
  /usecase {name} {정보}        참고자료   ├→ backend/docs/new-domain/{name}/{name}-v{n}.md
  /usecase 용어 {name}          용어 사전  ┘        (하나의 도메인 정의서)
      │  (요구 🔶 전부 확정 후)
      ▼
  /develop-design {name}       도메인 모델 → 같은 파일에 '## 도메인 모델' 추가
      │  (모델 🔶 전부 확정 후)
      ▼
  /implement {name}            developer ⇄ tester (5라운드) → code-reviewer (3회)
      ▼
  /docs-sync                   테스트 리포트 + REST Docs + main 문서 push
      ▼
  /domain-model {module}       모델 그림 → backend/docs/model/master.md

  ── 곁가지 (어느 단계에서든 · 같은 도메인 폴더에 쌓인다) ──
  /adr-create                  왜 그렇게 정했나 → {name}/{주제}-{날짜}.html
  /diagram {주제}               상태·순서·흐름 그림 → {name}/{주제}.md
  ```

- 지금 어느 단계인지 모르면 `/pm` 이 진단한다.
- **에이전트는 커밋하지 않는다.** 커밋은 스킬이 사용자 확인을 받고 한다.

## 규칙

- 한글로 대답
- MVP 범위만 집중, 과한 추상화 금지
- **주석은 최소로** — 기본은 주석 없음. "왜"와 깨지기 쉬운 계약만 남기고 설계 근거는 `docs/` 에
  쓴다 → [architecture.md "주석"](docs/rule/architecture.md)
- 테스트 없는 머지 금지 — 컨트롤러 테스트 = REST Docs
- 모듈 경계를 넘는 참조가 필요하면 **먼저 이벤트를 고려**한다
- 문서의 **🔶 표시는 미확정 결정** — 임의 확정 말고 사용자에게 확인
