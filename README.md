# AI로 마케팅 영상 딸깍

**글만 쓰면 영상이 나온다.** 텍스트만으로 **마케팅 쇼츠와 트렌딩 쇼츠를 만들고, 거기에 얹을
자막까지 생성하는 것**이 이 서비스의 목표다. 토스 미니앱으로 제공한다.

영상 편집 도구를 배우지 않고, 촬영도 하지 않고, 하고 싶은 말만 적으면 된다. 지금 구현이 서 있는
자리는 그중 **자막 파이프라인**이다 — 영상을 올리면 대본을 뽑아 주고, 사용자가 그 대본을 고쳐
확정하면 자막 파일(md)로 돌려준다.

이 레포가 지키려는 것은 처리 성공률이 아니라 **돌아올 자리**다. 미니앱은 토스 앱 안에서 돌아
알림 한 통으로 이탈하는데 처리에는 수 분이 걸린다. 그래서 모든 설계가 "이탈했다 돌아온 사용자가
결과물을 잃지 않는가"를 기준으로 서 있다.

---

## 레포 구조

> 레포 이름은 `youtube-creator`, 코드 네임스페이스와 이미지 이름은 `ytcreator` 다
> (`kang20.ytcreator.*`, `ghcr.io/kang20/ytcreator-backend`). 둘은 같은 것을 가리킨다.

```
youtube-creator/
├── README.md                  ← 지금 이 파일
├── CLAUDE.md                  레포 전체 규칙 (AI 에이전트 세션에 자동 로드)
├── .githooks/pre-commit       브랜치·영역 규칙을 실제로 강제하는 훅
├── .github/workflows/         배포·롤백·DB 마이그레이션 파이프라인
├── .claude/                   에이전트 스킬(파이프라인)과 서브에이전트 정의
├── docs/                      레포 공통 문서 — main 브랜치의 정본
│   ├── api/index.html         REST Docs 산출물 (프론트가 읽는 API 명세)
│   ├── planning/mvp-v1.md     기획
│   └── platform/              앱인토스 플랫폼 스펙 사본
└── backend/                   API 서버 (Spring Boot + Spring Modulith)
    ├── CLAUDE.md              백엔드 규칙 허브
    ├── docs/                  백엔드 문서 (아래 "문서 가이드")
    ├── deploy/                배포 스크립트·compose·Caddy·DDL
    └── src/
```

---

## 브랜치 규칙 — 훅이 강제한다

| 브랜치 | 담는 것 | 금지 |
|---|---|---|
| `main` | 문서(`.md`) · `assets/` · `docs/api/` | 코드 전부 |
| `backend` | 백엔드 코드·문서 | `frontend/` 변경 |
| `frontend` | 프론트 코드·문서 | `backend/` 변경 |

- **커밋 전 항상 `git pull`** — `origin/<브랜치>` 보다 뒤처져 있으면 커밋이 거부된다.
- **`backend` 브랜치 push 는 배포 트리거다.** `backend/**` 가 바뀌면 블루-그린 배포가 돌아간다.
- 커밋은 역할별로 나눈다: `feat` / `test` / `refactor` / `fix` / `docs` / `chore`. 섞지 않는다.

클론 후 **한 번은 반드시** 훅을 연결한다.

```bash
git config core.hooksPath .githooks
```

---

## 문서 가이드

문서가 많은 레포다. 규칙은 하나다 — **같은 사실을 두 곳에 적지 않는다.** 복사본은 반드시 먼저 낡는다.

### 정본이 어디인가

| 알고 싶은 것 | 정본 |
|---|---|
| 이 도메인이 무엇을 해야 하는가 | `backend/docs/new-domain/{name}/{name}-v{n}.md` |
| 모듈 경계·패키지 배치·동시성 골격·주석 분량 | `backend/docs/rule/architecture.md` |
| 테스트 종류·커버리지 기준 | `backend/docs/rule/testing.md` |
| 에러 코드 규약 | `backend/docs/rule/error-handling.md` |
| 컨트롤러 테스트 = 문서 규약 | `backend/docs/rule/rest-docs.md` |
| 토스 연동 판단 근거 | `backend/docs/rule/toss-integration.md` |
| 프론트가 읽는 API 계약 | `docs/api/index.html` (테스트가 만든다) |
| 로그·모니터링 | `backend/docs/ops/logging.md` |
| 보안 체인 | `backend/docs/security/` |

### 도메인 정의서 — 도메인 하나 = 폴더 하나 = 파일 하나

```
backend/docs/new-domain/
└── {name}/
    ├── {name}-v{n}.md              정의서 — 요구·참고자료·용어 사전·도메인 모델이 한 파일에
    ├── {주제}-{YYYY-MM-DD}.html    ADR — 왜 그렇게 정했나 (기각안과 이유까지)
    └── {주제}.md                   다이어그램 (Mermaid)
```

- **요구서와 설계서를 따로 두지 않는다.** 요구가 바뀔 때 모델이 같이 눈에 들어와야 한다.
- **용어 사전의 영문명이 코드 식별자의 계약이다.** 클래스·메서드·필드·에러코드는 그 표의 말만 쓴다.
- **버전을 올린다 = 파일을 rename 한다.** 이전 버전 파일을 남기지 않고, 걸린 링크를 같은 커밋에서 고친다.
  이력은 파일이 아니라 git 과 문서의 `## 변경 이력` 이 보관한다.
- **지운 항목은 빗금으로 남긴다.** "이런 규칙은 없다"가 아니라 **"있었는데 왜 없앴다"** 가 남아야
  같은 논의가 반복되지 않는다.
- **🔶 는 미확정 표시다.** `## 🔶 결정 필요` 에 모이고, **0건이 되기 전에는 다음 단계로 넘어가지 않는다.**
  미확정 위에 구현하면 확정될 때 코드와 테스트를 둘 다 다시 쓴다.

> `backend/docs/domain/` 의 5개(`auth.md`·`auth-design.md`·`payment.md`·`payment-design.md`·`subtitle.md`)는
> **구 양식 레거시(읽기 전용)** 다. 그 도메인은 코드가 정본이므로 선례 참조로만 읽는다.

### 문서가 코드로 가는 길

```
docs/api/index.html  ←  asciidoctor  ←  src/docs/asciidoc/*.adoc  ←  컨트롤러 테스트 스니펫
```

**테스트가 곧 문서다.** 컨트롤러 테스트가 깨지면 API 문서도 없다. 성공·실패 응답을 모두 문서화한다 —
실패 응답도 프론트 계약이다.

---

## 개발 가이드

### 새 기능을 넣는 순서

```
/usecase {name}          요구·용어 사전·참고자료   ┐
                                                 ├→ {name}-v{n}.md 한 파일에 쌓인다
/develop-design {name}   도메인 모델              ┘
        │  (🔶 0건이어야 다음으로)
        ▼
/implement {name}        developer ⇄ tester 루프 → code-reviewer 판정
        ▼
/docs-sync               테스트 리포트 + REST Docs 빌드 + main 에 문서 push
```

- 지금 어느 단계인지 모르면 `/pm` 이 진단한다.
- 설계 의사결정은 `/adr-create`, 상태·흐름 그림은 `/diagram` 으로 같은 도메인 폴더에 쌓는다.
- **에이전트는 커밋하지 않는다.** 커밋은 `/git-commit` 이 사용자 확인을 받고 한다.

### 아키텍처 — Spring Modulith

**패키지 = 애플리케이션 모듈**이고, 경계는 사람이 아니라 **테스트가 강제**한다.

```
{module}/
├── package-info.java        @ApplicationModule(displayName, allowedDependencies)
├── {X}Port.java             다른 모듈이 실제로 부르는 포트만 여기 온다
├── {Value}.java             경계를 넘는 값 객체·타입 ID
└── internal/                구현 전부 — 밖에서 참조하면 테스트가 깨진다
    ├── entity/              엔티티 + 상태 enum (+ 모듈 내부 전용 dto)
    ├── port/                자기 모듈 핸들러만 부르는 비공개 포트
    ├── handler/inbound/     컨트롤러·리스너·큐 소비자
    ├── handler/outbound/    리포지토리·외부 클라이언트
    └── service/             오케스트레이션 (+ support/ 부품)
```

두 테스트가 이 구조를 지킨다.

| 테스트 | 잡는 것 |
|---|---|
| `ModularityTest` | 순환 의존 · `internal` 침범 · 미허용 의존 |
| `ArchitectureConventionTest` | 모듈 **내부** 레이아웃 규약 R1~R7 (소스 스캔) |

핵심 규칙 몇 가지 — 자세한 것은 [architecture.md](backend/docs/rule/architecture.md).

- **모듈 루트에 있으려면 밖에서 실제로 쓰여야 한다.** 안 쓰이면 `internal` 로 내린다.
- **모듈 간 통신은 이벤트가 기본.** 직접 호출은 동기 응답이 반드시 필요할 때만.
- **다른 모듈 데이터는 타입화된 식별자로만** 가리킨다. 엔티티 참조도 원시 `Long` 도 금지.
- **동시성은 `UNIQUE` 제약을 심판으로 쓴다.** "조회해서 없으면 삽입"은 멱등의 근거가 되지 못한다.
- **주석은 최소로.** 기본은 주석 없음. "왜"와 어기면 조용히 깨지는 계약만 남기고
  설계 근거는 `docs/` 에 쓴다.

### 테스트

```bash
cd backend
./gradlew test                              # 전체 + REST Docs 스니펫 + 커버리지 리포트
./gradlew test --tests "*ModularityTest"    # 모듈 경계만
./gradlew check                             # 커버리지 게이트까지
```

- **커버리지 목표 100%, 게이트는 LINE 95% / BRANCH 90%.** 95는 실패선이고 목표는 100이다.
  새 기능의 변경 라인은 100을 채운다.
- 미커버 라인이 남으면 **"왜 테스트할 수 없는가"를 먼저 묻는다.** 대부분 테스트가 어려운 게 아니라
  설계가 새는 신호다(정적 의존, 시간·랜덤 직접 사용, 과한 방어 코드).
- **시간·랜덤은 주입받는다**(`Clock` 빈). 그래야 100%가 가능해진다.
- 동시성 테스트에는 `@Transactional` 을 붙이지 않는다 — 붙이면 커밋 대 커밋의 경쟁이 사라진다.
- 리포트: `backend/build/reports/jacoco/test/html/index.html`

### 로컬 실행

```bash
cd backend
./gradlew bootRun
```

`local` 프로파일이 `docker-compose.yml` 의 MySQL(호스트 3307)과 Redis(호스트 6380)를 자동으로 띄운다.
Docker Desktop 이 켜져 있어야 한다.

워커 큐는 **기본이 꺼짐**이다. 워커 없이도 개발이 되게 하기 위해서이고, 켜려면 이렇게 띄운다.

```bash
SUBTITLE_QUEUE_ENABLED=true ./gradlew bootRun
```

꺼져 있으면 워커 의뢰는 거부되고 완료 큐도 읽지 않는다. 켜졌는데 Redis 에 닿지 못하면 **기동 실패**다 —
조용히 뜨면 완료 통지를 아무도 읽지 않기 때문이다.

---

## 배포

> ⚠️ **자동 배포는 꺼져 있다 (2026-09-03).** 배포 준비 전이라 `deploy-backend.yml` 의 `push` 트리거를
> 주석 처리했다. 지금 `backend` 에 push 해도 배포는 돌지 않고, 필요할 때 **수동으로만** 돌린다.
> 준비가 끝나면 그 주석을 풀어 되살린다.

OCI VM 2대(앱·DB)에 **블루-그린**으로 올린다.

| 워크플로 | 트리거 |
|---|---|
| `deploy-backend.yml` | 수동 (`push` 트리거는 주석 처리 — 원래는 `backend` push) |
| `deploy-db.yml` | `backend` push (`docker-compose.db.yml` 변경 시) |
| `db-migrate.yml` | 수동 — SQL 파일 지정 |
| `rollback.yml` | 수동 |

- **운영 스키마는 배포로 바뀌지 않는다**(`ddl-auto: validate`). 스키마 변경은
  `backend/deploy/sql/` 에 DDL 을 넣고 `db-migrate` 를 **먼저** 돌린 뒤 배포한다.
- 배포는 새 색을 띄우고 `/actuator/health` 가 `UP` 이 되어야 트래픽을 넘긴다. 실패하면 되돌린다.
- 인프라 상태가 궁금하면 `/infra` 스킬이 답한다. **비밀번호·키는 다루지 않는다.**

---

## 처음 온 사람이 읽을 순서

1. 이 README — 무엇을 만들고 어떻게 일하는가
2. [`CLAUDE.md`](CLAUDE.md) — 인증/식별 모델(익명키 단일 식별)과 레포 규칙
3. [`backend/docs/rule/architecture.md`](backend/docs/rule/architecture.md) — 코드를 어디에 둘 것인가
4. [`backend/docs/new-domain/subtitle/subtitle-v3.md`](backend/docs/new-domain/subtitle/subtitle-v3.md)
   — 정의서가 어떻게 생겼는지 보는 가장 좋은 예이자, 지금 구현이 서 있는 자리다
5. [`docs/api/index.html`](docs/api/index.html) — 프론트와의 계약
