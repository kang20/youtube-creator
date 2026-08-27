---
name: implement
description: "\"구현\", \"개발 시작\", \"implement {name}\", \"정의서대로 만들어줘\", \"구현 착수\" 요청 시 활성화. 설계 확정된 도메인 정의서 backend/docs/new-domain/{name}/{name}-v{n}.md 하나를 입력으로 developer ⇄ tester 내부 루프(최대 5라운드)를 돌리고, code-reviewer 가 요구 누락·코드 오염·테스트 누락을 판정해 RETRY 면 내부 루프를 되돌린다(최대 3회). backend 브랜치 전용."
---

# implement — 도메인 정의서 기반 이중 루프 구현

**도메인 정의서 한 파일**(`backend/docs/new-domain/{name}/{name}-v{n}.md`)을 입력으로 두 개의 루프를 돌린다.

```
외부 루프(최대 3회) ── code-reviewer 가 판정
  └ 내부 루프(최대 5라운드) ── developer → tester
```

내부 루프는 **"동작하는가"** 를 닫고, 외부 루프는 **"문서대로인가"** 를 닫는다.
테스트가 전부 초록인데 요구 하나가 구현되지 않은 상태가 실제로 생기기 때문에 루프를 둘로 나눈다.

**이 스킬은 코드를 직접 쓰지 않는다.** 세 에이전트를 순서대로 부르고, 루프를 관리하고,
막히면 사용자에게 올린다. 직접 구현하면 에이전트를 나눈 의미가 없다.

## 입력

- `{name}` — 도메인명
- **`backend/docs/new-domain/{name}/{name}-v{n}.md`** — 유일한 사양 입력. 상태가 **"설계 확정"** 이어야 한다
  - 요구·참고자료·용어 사전·도메인 모델이 **한 파일**에 있다. 세 에이전트가 같은 파일을 본다
  - 양식 선례: [payment-v2.md](../../../backend/docs/new-domain/payment/payment-v2.md)
- ⚠️ `backend/docs/domain/*.md` 5종은 **레거시(읽기 전용)** 다. 새 도메인은 `new-domain/` 에만 쓴다.
  레거시 문서를 이 스킬의 입력으로 쓰지 않는다 — 번호 체계도 섹션 구조도 다르다

## 에이전트 역할 분담

| 에이전트 | 읽는다 | 쓴다 | 안 한다 |
|---|---|---|---|
| [developer](../../agents/developer.md) | 도메인 정의서 + [architecture.md](../../../backend/docs/rule/architecture.md) + [error-handling.md](../../../backend/docs/rule/error-handling.md) + 기존 코드 | `src/main/**` | 테스트 작성, 커밋 |
| [tester](../../agents/tester.md) | 도메인 정의서 + [testing.md](../../../backend/docs/rule/testing.md) | `src/test/**`, `src/docs/asciidoc/**`, `workspace/{name}/v{n}/requirements.md` | 프로덕션 수정, 커밋 |
| [code-reviewer](../../agents/code-reviewer.md) | 도메인 정의서 + 변경 코드 + `requirements.md` + 규칙 문서 | (읽기 전용) | 코드 수정, 커밋 |

**에이전트는 서로를 직접 호출하지 않는다.** `backend/workspace/{name}/v{n}/` 의 보고서로만 통신하고,
호출은 이 스킬이 한다 → [references/handoff.md](references/handoff.md)

## 참조

| 무엇이 필요할 때 | 어디를 |
|---|---|
| workspace 파일 규격·원인 분류 | [references/handoff.md](references/handoff.md) |
| 테스트 종류·커버리지 기준 | [testing.md](../../../backend/docs/rule/testing.md) |
| 모듈 경계·패키지 배치·주석 판정선 | [architecture.md](../../../backend/docs/rule/architecture.md) |
| ErrorCode·BusinessException 규약 | [error-handling.md](../../../backend/docs/rule/error-handling.md) |
| 컨트롤러 테스트 = 문서 규약 | [rest-docs.md](../../../backend/docs/rule/rest-docs.md) |

**수치·경로·네이밍을 이 스킬이 다시 적지 않는다.** 규칙 문서가 정본이고, 복사본은 먼저 낡는다.

## 절차

### 0. 사전 검증

전부 통과해야 1단계로 간다. 하나라도 걸리면 **중단하고 사용자에게 무엇이 막았는지 말한다.**

- **backend 브랜치**인지 확인한다. 코드는 backend 브랜치에서만 쓴다
- `backend/docs/new-domain/{name}/{name}-v*.md` 가 **정확히 하나** 존재하는지 확인한다
  - 없으면 `/usecase {name}` → `/develop-design {name}` 을 안내하고 중단한다
  - 둘 이상이면 rename 이 누락된 것이다. 정본이 어느 쪽인지 사용자에게 묻는다 — 추측하면 옛 사양으로 구현한다
- 상태 헤더가 **"설계 확정"** 인지 확인한다
  - "요구 초안"·"요구 확정" 이면 모델이 없다 → `/develop-design {name}` 안내 후 중단
  - "모델 초안" 이면 아직 확정 전이다 → 확정 절차를 먼저 밟게 한다
  - **`구현 완료` 면 이미 끝난 도메인이다** — 재구현인지 사용자에게 확인하고 진행 여부를 받는다
- **🔶 잔여가 0건**인지 확인한다. `## 🔶 결정 필요` 에 항목이 남아 있으면 중단한다
  - 미확정 위에 구현하면 확정될 때 코드와 테스트를 둘 다 다시 쓴다
  - 상태 헤더의 건수와 실제 항목 수가 어긋나면 문서가 갱신되지 않은 것이다. 사용자에게 확인한다
- `## 도메인 모델` 섹션이 있는지 확인한다. 요구만 있고 모델이 없으면 developer 가 설계를 시작하게 된다
- `## 용어 사전` 은 **필수가 아니다.** 없어도 막지 않고 **경고 한 줄만 남긴다** —
  "용어 사전 없음 — 이름 계약은 도메인 모델의 영문명을 따른다"
  (`/usecase 용어 {name}` 을 호출했을 때만 생기는 섹션이라 없는 것이 정상이다)
- `backend/workspace/{name}/v{n}/blockers.md` 가 **이미 있으면** 항목마다 새 정의서 버전으로 해소됐는지
  **사용자에게 확인한다.** 해소분은 같은 디렉토리의 `blockers-resolved-{날짜}.md` 로 옮긴 뒤 시작한다 —
  옛 블로커가 남아 있으면 중단 조건이 첫 라운드부터 걸린다
- **직전 버전 디렉토리 `backend/workspace/{name}/v{n-1}/blockers.md` 도 확인한다.**
  있으면 그 항목이 이번 정의서로 해소됐는지 사용자에게 확인하고, 미해소분은 `v{n}/blockers.md` 로 옮겨 적는다.
  이 확인은 **`v{n}/blockers.md` 존재 여부와 무관하게 돈다** — blockers 로 멈춰 정의서를 올린 경우
  `v{n}/` 은 아직 만들어지지도 않았으므로, 위 항목의 하위로 두면 이 검사가 통째로 건너뛰어진다.
  버전이 갈리면 옛 블로커가 시야 밖으로 나가, 일부만 해소됐을 때 나머지가 조용히 사라진다
- **`backend/workspace/{name}/v{n}/` 를 준비한다** — `{n}` 은 이번에 읽은 정의서의 버전이다.
  **gitignore 대상이라 커밋되지 않는다**
  - workspace 는 **정의서 버전으로 갈린다.** 정의서가 v2 로 올랐으면 `v2/` 를 **새로 만들고
    k=1 · r=1 로 시작한다.** `v1/` 은 지우지 않고 그대로 남긴다 —
    옛 버전의 판정 기록이 사라지면 무엇이 왜 바뀌었는지 대조할 근거가 없다
  - **`requirements.md` 만 예외다. 직전 버전이 있으면 거기서 복사해 온 뒤 갱신하고,
    첫 버전(v1)이면 새로 만든다. 같은 버전 안에서는 재생성하지 않는다.**
    REQ-번호를 보존하기 위해서다. 재생성하면 번호가 재배열돼 이미 박힌 테스트 주석이
    다른 요구를 가리킨다. 버전이 오르며 사라진 요구의 처리는 [handoff.md](references/handoff.md)

### 1. 구현 — `developer`

**최초 구현** (k=1, r=1)

```
Agent(subagent_type="developer",
      prompt="{name} 도메인을 backend/docs/new-domain/{name}/{name}-v{n}.md 대로 구현한다.
              리뷰 회차 {k} / 라운드 {r}.
              사양은 이 파일 하나다 — 요구 섹션과 '## 도메인 모델' 을 함께 읽는다.
              용어 사전이 있으면 그 영문명이 코드 식별자의 계약이다.
              없으면 '## 도메인 모델' 의 {한글명}(English) 의 English 를 쓴다.
              보고서는 backend/workspace/{name}/v{n}/review{k}-round{r}-dev.md 에
              handoff 규격대로 쓴다.")
```

재구현은 **두 갈래다.** 처리할 지시의 출처가 다르므로 프롬프트를 섞지 않는다.

**(a) 테스트 실패 재구현** (r ≥ 2) — 직전 라운드의 테스트 보고만 처리한다.

```
Agent(subagent_type="developer",
      prompt="{name} 재구현. 리뷰 회차 {k} / 라운드 {r}.
              backend/workspace/{name}/v{n}/review{k}-round{r-1}-test.md 의 수정 지시만 처리한다.
              지시에 없는 것을 겸사겸사 고치지 않는다.
              보고서는 backend/workspace/{name}/v{n}/review{k}-round{r}-dev.md 에 쓴다.")
```

**(b) 리뷰 재개 첫 라운드** (k ≥ 2, r = 1) — 직전 회차의 리뷰 판정만 처리한다.
이전 회차의 테스트 보고서는 참조하지 않는다 (r=1 에는 직전 라운드 테스트 보고가 존재하지 않는다).

```
Agent(subagent_type="developer",
      prompt="{name} 재구현. 리뷰 회차 {k} / 라운드 1.
              backend/workspace/{name}/v{n}/review-{k-1}.md 중 '→ developer' 로 표시된 항목만 처리한다.
              이전 회차의 테스트 보고서는 보지 않는다.
              지시에 없는 것을 겸사겸사 고치지 않는다.
              보고서는 backend/workspace/{name}/v{n}/review{k}-round1-dev.md 에 쓴다.")
```

### 2. 테스트 — `tester`

```
Agent(subagent_type="tester",
      prompt="{name} 도메인 테스트. 리뷰 회차 {k} / 라운드 {r}.
              backend/workspace/{name}/v{n}/requirements.md 는 있으면 갱신한다.
              없으면 직전 버전이 있는 경우 거기서 복사해 온 뒤 갱신하고,
              첫 버전(v1)이면 새로 만든다. 같은 버전 안에서는 재생성하지 않는다.
              도메인 정의서의 요구 섹션 불릿 전부 + '## 도메인 모델' 의 '#### 규칙' 불릿 전부를
              REQ-1..REQ-n 으로 옮긴다. 기존 REQ-번호는 재배열하지 않는다.
              요구 섹션은 상태 헤더 다음의 첫 번째 '## ' 섹션이다. 제목 문자열로 찾지 않는다.
              그 다음 덮이지 않은 REQ 항목의 테스트를 쓰고 실행한다.
              신규 도메인이고 HTTP 컨트롤러가 있으면 backend/src/docs/asciidoc/{name}.adoc 을 만들고
              index.adoc 에 include 한 줄을 넣는다. 컨트롤러가 없으면 이 항목은 N/A 다.
              통과·실패 무관하게 backend/workspace/{name}/v{n}/review{k}-round{r}-test.md 를
              handoff 규격대로 쓴다 — 통과면 '## 실패 상세' 는 생략한다.")
```

재개 회차의 첫 라운드(k ≥ 2, r = 1)는 **직전 회차의 리뷰 판정을 출처로 삼는다.**

```
Agent(subagent_type="tester",
      prompt="{name} 테스트 보강. 리뷰 회차 {k} / 라운드 1.
              backend/workspace/{name}/v{n}/review-{k-1}.md 중 '→ tester' 로 표시된 항목만 처리한다.
              requirements.md 는 새로 만들지 않고 갱신한다 — REQ-번호는 회차를 넘어 유지된다.
              이전 회차의 테스트 보고서는 보지 않는다.
              보고서는 backend/workspace/{name}/v{n}/review{k}-round1-test.md 에 쓴다.")
```

### 3. 내부 루프 판정

`review{k}-round{r}-test.md` 와 tester 판정을 읽고 갈린다. **이 보고서는 통과 라운드에도 있다.**

- **통과** → 4단계로 간다. 통과의 정의는 tester 판정 기준 셋을 전부 만족한 것이다
  1. `requirements.md` 의 모든 REQ 항목에 덮는 테스트가 있다 (`N/A(사유)` 는 사유가 적혀 있다)
  2. [testing.md](../../../backend/docs/rule/testing.md) 의 테스트 컨벤션을 따랐다
  3. 변경 파일 라인 커버리지가 100% 에 수렴한다 (도달 불가 방어선은 근거 명시)
- **실패** → 라운드를 올려 1단계로 돌아간다

**내부 루프 중단 조건 — 셋 중 하나면 즉시 멈추고 사용자에게 올린다**

- **5라운드를 넘겼다.** 남은 실패와 라운드별로 시도한 것을 보고한다
- **같은 테스트가 2라운드 연속 같은 이유로 실패했다.** 접근이 틀린 것이라 더 돌려도 같은 결과다.
  라운드별 파일이 남아 있으므로 같은 실패인지 대조할 수 있다
- **이번 실행에서 새 블로커가 등록됐다.** 정의서로 판정 불가한 것을 추측으로 넘기면
  잘못된 구현 위에 테스트가 쌓인다. 해소는 `/develop-design {name}` 으로 도메인 모델을 보강하는 것이 정석이다
  (0단계에서 해소 확인을 끝낸 옛 항목은 중단 사유가 아니다 — 파일 존재가 아니라 **신규 등록**이 기준이다)

**라운드마다 한 줄로 진행 상황을 사용자에게 알린다** (실패 n건 → n건, 커버리지 n%).
루프가 조용히 5번 돌면 사용자는 무슨 일이 일어나는지 모른다.

### 4. 코드 리뷰 — `code-reviewer`

내부 루프가 통과한 뒤에만 호출한다. 테스트가 빨간 상태에서 리뷰하면 지적이 실패 원인에 묻힌다.

```
Agent(subagent_type="code-reviewer",
      prompt="{name} 도메인 구현 리뷰. 리뷰 회차 {k} (최대 3).
              근거 문서는 backend/docs/new-domain/{name}/{name}-v{n}.md 다.
              세 가지만 RETRY 로 판정한다 — 요구 누락 · 코드 오염 · 테스트 누락.
              backend/workspace/{name}/v{n}/requirements.md 를 도메인 정의서와 재대조해
              tester 가 추출에서 빠뜨린 요구를 잡는다.
              요구 섹션은 상태 헤더 다음의 첫 번째 '## ' 섹션이다. 제목 문자열로 찾지 않는다.
              지적마다 담당을 '→ developer' 또는 '→ tester' 로 표시한다.
              첫 줄은 '## 코드 리뷰: {name} ({k}회차)' 이고,
              마지막 줄은 '## 판정: PASS' 또는 '## 판정: RETRY ({n}건)' 이다 —
              이 줄의 {n} 은 정의서 버전이 아니라 🔴 항목 수다. 🟡·🔵 는 세지 않는다.")
```

### 5. 외부 루프 판정

**판정을 `backend/workspace/{name}/v{n}/review-{k}.md` 로 남긴다 — PASS·RETRY 무관하다.**
**code-reviewer 출력을 그대로 저장한다.** 요약하거나 재편집하지 않는다 —
양식의 정본은 [code-reviewer](../../agents/code-reviewer.md) 의 출력 형식이다.
**첫 줄이 `## 코드 리뷰: {name} ({k}회차)` 이고, 마지막 줄이 `## 판정: ...` 이다.**
pm 이 이 마지막 줄을 grep 하므로 뒤에 아무것도 덧붙이지 않는다.
PASS 여도 파일을 남긴다 — 🔴 절은 비고 🟡·🔵·✅ 만 남지만, 이 파일이 pm 의 5단계 완료 탐지 근거이고
마지막 RETRY 파일만 남으면 끝난 구현을 미완으로 오판한다.

- **PASS** → 6단계로 간다
- **RETRY** → **1단계로 돌아간다**
  - **배분은 항목에 적힌 담당(`→ developer` / `→ tester`)을 그대로 따른다.**
    카테고리(요구 누락/코드 오염/테스트 누락)로 추정하지 않는다 —
    '요구 누락' 안에도 담당이 tester 인 항목(requirements.md 추출 누락)이 있다
  - 표시가 없는 항목은 code-reviewer 에게 되묻는다. 추측해 배분하면 남의 영역을 건드린다
  - 재개하면 `{k}` 가 1 오르고 `{r}` 은 **1부터 다시 센다.** 리뷰 회차가 바뀌면 다른 문제를 푸는 것이다.
    **단 `requirements.md` 와 REQ-번호는 회차를 넘어 유지된다** —
    재생성하면 번호가 재배열돼 이미 박힌 테스트 주석이 다른 요구를 가리킨다
  - 재개 첫 라운드의 프롬프트는 1·2단계의 **(b) 갈래**를 쓴다. `review{k}-round0-test.md` 는 없다

**외부 루프 중단 조건**

- **3회차에도 RETRY** 면 멈춘다. 남은 지적과 회차별로 시도한 것을 사용자에게 보고한다
- **RETRY 를 트리거하는 것은 위 세 판정 항목뿐이다.** 그 밖의 품질 지적은 루프를 돌리지 않고
  사용자 판단으로 남긴다 — 취향 지적으로 루프가 무한해지면 판정선이 사라진다

**회차마다 한 줄로 진행 상황을 사용자에게 알린다** (리뷰 {k}회차: RETRY {n}건 → 내부 루프 재개).

### 6. 결과 보고

```
## {name} 구현 완료

- 리뷰 회차: {k}회 / 마지막 회차 내부 라운드: {r}회
- 테스트: {통과}/{전체} · ModularityTest 통과
- 변경 파일 라인 커버리지: {n}%
- requirements.md: 전체 {n}건 — 덮임 {n} / 미덮임 {n} / N/A {n}
- 신규 파일 {n}개 / 수정 {n}개

### 코드 리뷰 결과
{code-reviewer 최종 출력 그대로}

### 다음
- 커밋 → `/docs-sync` 로 마감
```

- **code-reviewer 출력은 그대로 싣는다.** 요약하면 판정 근거가 사라진다
- 미덮임이나 미해결 지적이 남은 채 끝났다면 **"완료"라고 쓰지 않는다.** 무엇이 남았는지 먼저 적는다
- 보고 뒤 도메인 정의서의 **상태 헤더를 `구현 완료({날짜})` 로 갱신한다 — 이것만 예외다.**
  이 헤더가 갱신되지 않으면 pm 이 끝난 도메인을 계속 "설계 확정" 으로 읽는다.
  헤더 한 줄 외에 요구·용어·모델은 건드리지 않는다

### 7. 커밋 (사용자 확인 후)

역할별로 나눠 커밋한다 — 혼합하지 않는다.

| 커밋 | 담는 것 |
|---|---|
| `feat({name})` | `src/main/**` |
| `test({name})` | `src/test/**` |
| `docs(api)` | `src/docs/asciidoc/{name}.adoc` · `index.adoc` include |
| `docs({name})` | `backend/docs/new-domain/{name}/{name}-v{n}.md` 상태 헤더 갱신 · `backend/CLAUDE.md` 모듈 목록 |
| `chore(db)` | 스키마·마이그레이션 |

체크리스트

- [ ] 신규 모듈이면 `backend/CLAUDE.md` 의 모듈 목록을 갱신했다 (`docs({name})` 커밋)
- [ ] `backend/workspace/` 가 스테이지에 없다
- [ ] 도메인 정의서 상태 헤더가 `구현 완료({날짜})` 다

**커밋은 이 스킬이 사용자 확인을 받고 한다.** 에이전트는 커밋하지 않는다.

## 요구 추적 — 번호가 문서에 없는 대신

도메인 정의서는 U1·§5-2 같은 번호를 쓰지 않는다. 추적은 **workspace 체크리스트**가 대신한다.

- `backend/workspace/{name}/v{n}/requirements.md` 를 **tester 가 없으면 만들고, 있으면 갱신한다**
  (**직전 버전이 있으면 거기서 복사해 온 뒤 갱신하고, 첫 버전(v1)이면 새로 만든다.**
  같은 버전 안에서는 재생성하지 않는다)
  - 도메인 정의서의 요구 섹션 불릿 **전부** + `## 도메인 모델` 의 `#### 규칙` 불릿 **전부** 를
    `REQ-1`..`REQ-n` 으로 옮긴다. 원문 그대로 옮기고 요약하지 않는다 — 요약하는 순간 판정 기준이 바뀐다
  - **요구 섹션은 상태 헤더 다음의 첫 번째 `## ` 섹션이다. 제목 문자열로 찾지 않는다** —
    제목의 `{도메인명}` 은 한글이어도 영문이어도 되므로(선례 `## 결제 도메인`) 이름으로 grep 하면 깨진다
  - **회차를 넘어, 그리고 버전을 넘어 하나만 유지한다.** 재개해도 재생성하지 않는다
  - 라운드마다 갱신한다. 양식은 [handoff.md](references/handoff.md)
- 테스트 주석은 이 번호를 쓴다 — `/** REQ-12 — 같은 주문을 두 번 지급해도 잔량은 한 번만 오른다 */`
  딱 한 줄이면 된다 ([architecture.md "주석"](../../../backend/docs/rule/architecture.md))
- 번호는 **`REQ-{n}`** 이다. `R{n}` 은 [architecture.md](../../../backend/docs/rule/architecture.md) 의
  공개 표면 규칙 R1~R7 과 충돌한다. 아키텍처 규칙을 인용할 때는 `architecture.md R1` 처럼 문서명을 앞에 붙인다
- **code-reviewer 가 이 체크리스트를 도메인 정의서와 재대조한다.**
  tester 가 추출에서 빠뜨린 요구를 잡는 것이 목적이다 — 체크리스트만 보면 빠뜨린 줄은 영원히 안 보인다
- `N/A` 남발은 커버리지 판정을 무의미하게 만든다. 사유 없는 `N/A` 는 미덮임으로 다룬다

## 성공 조건

- [ ] 도메인 정의서의 요구·규칙이 **전부 `requirements.md` 에 있고**, 각 항목에 덮는 테스트가 있다
- [ ] `./gradlew check` 통과 — 테스트 + `ModularityTest` + 커버리지 게이트
- [ ] 이번 도메인 변경 파일의 라인 커버리지 100% (도달 불가 라인은 근거 명시)
- [ ] `backend/src/docs/asciidoc/{name}.adoc` 이 있고 `index.adoc` 에 include 돼 있다
      — **신규 도메인이고 HTTP 컨트롤러가 있을 때다.**
      컨트롤러가 없는 모듈(이벤트 구독 전용 등)은 이 항목이 **N/A** 다
- [ ] **이름이 계약을 따른다** — 용어 사전이 있으면 그 영문명이, 없으면 `## 도메인 모델` 의
      `{한글명}({English})` 의 English 가 코드 식별자다. 둘 다 없는 개념은 blockers 로 올라갔다
- [ ] **주석이 최소다** — 정의서를 옮겨 적은 주석, 기계적 javadoc, `@param`/`@return` 이 없다
      → [architecture.md "주석"](../../../backend/docs/rule/architecture.md) 판정선
- [ ] `code-reviewer` 최종 판정이 **PASS** 이고, 출력이 사용자에게 **그대로** 전달됐다
- [ ] `blockers.md` 에 **이번 실행에서 등록된 미해소 항목이 없다** (또는 사용자가 인지하고 진행을 승인했다)
- [ ] `backend/workspace/` 가 커밋되지 않았다

## 안전장치

- **설계 확정 전 구현 금지** — 🔶 가 남은 채로 시작하지 않는다. 확정되면 코드와 테스트를 둘 다 다시 쓴다
- **에이전트 영역 침범 감시** — 라운드마다 `git status --short` 로 확인한다.
  developer 가 `src/test/**` 를, tester 가 `src/main/**` 를 건드렸으면 **되돌리고 재지시한다**
  (분리가 무너지면 아무도 사양을 지키지 않게 된다)
- **커버리지 제외 추가 금지** — `coverageExclusions` 를 늘려 게이트를 통과시키지 않는다.
  정말 필요하면 사유와 함께 사용자 판단을 받는다
- **리뷰 지적을 자동 반영하지 않는다** — RETRY 트리거 3항목 외의 지적은 루프에 넣지 않고 사용자에게 넘긴다
- **루프를 조용히 돌리지 않는다** — 라운드마다·회차마다 한 줄 보고
- **사양(요구·용어·모델)을 이 스킬이 고치지 않는다. 상태 헤더 갱신만 예외다** —
  구현하며 사양이 달라져야 한다면 `/develop-design {name}` 몫이다.
  구현 편의로 문서를 고치면 문서가 코드의 사본이 된다

## 파이프라인 위치

```
/usecase {name}              요구      ┐
/usecase {name} {정보}        참고자료   ├→ backend/docs/new-domain/{name}/{name}-v{n}.md
/usecase 용어 {name}          용어 사전  ┘        (하나의 도메인 정의서)
    │  (요구 🔶 전부 확정 후)
    ▼
/develop-design {name}       도메인 모델 → 같은 파일에 '## 도메인 모델' 추가
    │  (모델 🔶 전부 확정 후)
    ▼
/implement {name}            developer ⇄ tester (5라운드) → code-reviewer (3회)   [이 스킬]
    ▼
/docs-sync                   테스트 리포트 + REST Docs + main 문서 push
    ▼
/domain-model {module}       모델 그림 → backend/docs/model/master.md
```
