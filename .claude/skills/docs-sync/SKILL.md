---
name: docs-sync
description: "구현 피처의 마감 스킬 — 테스트 실행·커버리지 리포트 작성 + REST Docs(API 명세) 빌드 + 문서를 main 에 커밋·push 를 한 번에. \"docs sync\", \"문서 동기화\", \"테스트 리포트\", \"커버리지\", \"API 문서 배포\", \"명세 반영\", \"마감\" 요청 시 활성화. backend 브랜치에서 호출."
---

# docs-sync — 피처 마감(테스트 리포트 + 문서 빌드 + main 동기화)

> 한 도메인/피처 구현을 끝낸 뒤 호출하는 **마감 스킬**. 세 가지를 한 번에 한다:
> ① 이번 피처의 **테스트 실행 + 커버리지 리포트 작성**,
> ② **REST Docs(API 명세) HTML 빌드**,
> ③ 문서(`docs/**`)를 **main 에 커밋 + origin push**.
> 코드는 절대 main 에 push 하지 않는다 — 코드는 backend 브랜치에 남는다.
> (구 `b-test-report` 는 이 스킬의 1단계로 흡수됐다.)

## 트리거 키워드

`docs sync`, `문서 동기화`, `문서 푸시`, `테스트 리포트`, `커버리지`, `API 문서 배포`, `명세 반영`, `피처 마감`, `마감`

> 구현 도중 빠르게 테스트만 돌릴 땐 스킬 없이 `./gradlew test` 를 쓴다.
> 이 스킬은 **피처를 끝내고 문서까지 내보내는 한 방** 용도다.

## 대상 파일 (이것만 main 에 push)

1. **REST Docs API 명세**: `docs/api/**` (**HTML 산출물만**, adoc 소스는 backend 로컬에만)
2. **기획/계약 문서**: `docs/**/*.md` (prd.md, server/api-spec.md, server/prd-server.md 등)

> 위 경로 외 파일(코드·테스트·설계문서 `backend/docs/**`)은 **절대 main 에 커밋하지 않는다.**

---

## 동작 흐름

### Step 0: 사전 검증

```bash
branch=$(git branch --show-current)   # backend 아니면 중단
```

- backend 브랜치가 아니면 경고 후 중단(테스트·문서 동기화 모두 backend 기준).

### Step 1: 테스트 실행 + 커버리지 리포트 (구 b-test-report)

```bash
cd backend && ./gradlew clean test --console=plain
```

- 컨트롤러 테스트 → `build/generated-snippets/` 스니펫
- Modulith 구조 검증 → 모듈 경계 규칙 검증
- JaCoCo → 커버리지(`finalizedBy`), 게이트 `jacocoTestCoverageVerification`(라인 90%)

**테스트 실패 → 여기서 중단.** 깨진 상태로 문서를 내보내지 않는다.

**이번 피처 범위 식별** — 무엇을 리포트할지:

```bash
git diff --name-only origin/backend...backend        # 이번에 푸시할 커밋들의 변경 파일
git diff --name-only origin/backend...backend -- '*.java' | grep 'src/main'   # 커버리지 대상
```

**커버리지 분석 (변경 파일 100% 기준)** — 전역 게이트는 LINE 95%/BRANCH 90%지만,
**이번 피처로 신규 생성·리팩터링한 `src/main` 파일은 라인 100%** 를 확인한다.
jacoco XML 을 파싱해 변경 파일만 뽑는 게 정확하다:

```bash
# 예: build/reports/jacoco/test/jacocoTestReport.xml 에서 클래스별 LINE missed>0 추출
```

- 미달 라인이 있으면 → (a) 테스트 추가, (b) 죽은 코드 제거, (c) 도달 불가 방어선이면 근거 명시.
  **그냥 두지 않는다** — 설계서(`{name}-design.md`) §10 의 커버리지 준수 항목과 대조.

### Step 2: 리포트 작성 (사용자에게 보고)

아래 형식으로 **이번 피처** 결과를 보고한다. 파일로 남기지 않고 응답 본문에 싣는다.

```
## {피처} 테스트 리포트

- 전체: N개 (성공 N / 실패 0) · Modulith 구조 검증 통과 · 소요 Ns
- 이번 피처 테스트 케이스:
  - {Domain}ControllerTest — vote/cancel + 실패 스니펫(409/400/404/401)
  - {Domain}ServiceIntegrationTest — 멱등·409·집계
  - {Domain}ConcurrencyTest — R1~R3 (동시성)
  - ...
- REST Docs 스니펫(신규): {name}-vote, {name}-cancel, ...
- 커버리지: 전체 라인 N% (게이트 LINE 95%/BRANCH 90% 통과)
  - **이번 변경 파일: {n}/{n}줄 = 100%** ✅  (또는 미달 라인과 사유)
```

### Step 3: REST Docs(API 명세) 빌드

API 명세가 바뀐 경우 HTML 을 재생성한다:

```bash
cd backend && ./gradlew asciidoctor --console=plain   # src/docs/asciidoc → docs/api/index.html
```

- adoc(`src/docs/asciidoc/*.adoc`)을 이번 피처에 맞게 갱신했는지 먼저 확인
  (신규 도메인이면 `{name}.adoc` + `index.adoc` include 한 줄).
- `docs/server/api-spec.md`(프론트 계약 정본)도 이번 변경을 반영했는지 확인.

### Step 4: 빌드 산출물 backend 커밋

`docs/api/` HTML 은 **backend 브랜치에 커밋되어 있어야** Step 6 에서 `git checkout backend -- docs/api/` 로 가져올 수 있다.

```bash
git add docs/api/ && git commit -m "docs(api): {name} REST Docs HTML 재생성"
```

- adoc 소스는 backend 로컬 커밋만(origin push X, rest-docs 규칙).

### Step 5: backend 작업 보존 + main 전환

```bash
git stash --include-untracked -m "docs-sync: 문서 동기화 전 stash"
git checkout main
git pull --rebase --autostash origin main
```

### Step 6: 문서 파일만 backend 에서 가져오기

```bash
git checkout backend -- docs/api/          # REST Docs 산출물(HTML)
git checkout backend -- docs/server/       # api-spec.md 등 계약 문서
# 필요 시 docs/prd.md 등 개별 지정 — docs/ 전체를 무분별하게 가져오지 않는다
```

### Step 7: 커밋 + push (사용자 확인 후)

- 변경 파일만 구체적으로 `git add` (`git add .` 금지).
- **push 전에 대상 파일 목록을 사용자에게 보여주고 확인받는다.**
- 커밋: `docs(api): {name} 명세 반영` / `docs(prd): ...` 형식.

```bash
git diff --cached --name-only        # 대상 경로(docs/**) 외 파일 있으면 즉시 abort
git commit -m "docs(api): {name} API 명세 반영"
git push origin main
```

### Step 8: backend 복귀

```bash
git checkout backend
git stash pop
```

---

## 안전장치

- **테스트 실패 시 전체 중단** — 리포트만 보고하고 문서는 내보내지 않는다.
- **변경 파일 커버리지 100% 확인** — 미달이면 사용자에게 알리고, 마감 진행 여부를 확인받는다.
- **대상 파일 외 변경 감지 시 중단** — `git diff --cached --name-only` 에 `docs/**` 외 경로가 있으면 abort.
- **docs/api/ 미커밋 시 중단** — HTML 이 backend 에 커밋되어 있지 않으면 Step 4 를 먼저.
- **stash 충돌 시** — 자동 pop 실패하면 `git stash list` 를 보여주고 수동 해결 안내.
- **코드는 main 에 안 올린다** — backend 코드/설계문서(`backend/docs/**`)는 backend 브랜치에만.
- **push 전 반드시 사용자 확인.**

## 주의

- 이 스킬은 **문서를 main 에 내보내는 것**까지다. **backend 브랜치를 origin 에 push(배포 트리거)하는 것은 별개** — 사용자가 명시적으로 배포를 지시할 때 수행한다.
- 코드 커밋은 backend 브랜치에 `/git-commit` 으로. 이 스킬은 문서 산출물만 다룬다.

## 자주 쓰는 변형

```bash
./gradlew test --tests "*ModularityTest"     # 아키텍처만
./gradlew test --tests "*{Name}ControllerTest" # 특정 도메인만
./gradlew clean test asciidoctor               # 테스트 + 문서까지 로컬 빌드
```
