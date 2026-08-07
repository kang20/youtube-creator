---
name: implement
description: "\"구현\", \"개발 시작\", \"implement {name}\", \"설계서대로 만들어줘\", \"구현 착수\" 요청 시 활성화. 확정된 설계서를 근거로 개발·테스트·리뷰 에이전트를 순서대로 돌려 도메인 하나를 구현한다. 테스트가 요구를 전부 덮고 커버리지가 100%에 수렴할 때까지 개발↔테스트를 반복한 뒤 코드 리뷰로 마감한다. backend 브랜치 전용."
---

# implement — 설계서 기반 구현 사이클

`{name}-design.md` 를 입력으로 **개발 → 테스트 → (실패 시 반복) → 코드 리뷰**를 조율한다.

**이 스킬은 코드를 직접 쓰지 않는다.** 세 에이전트를 순서대로 부르고, 루프를 관리하고,
막히면 사용자에게 올린다. 직접 구현하면 에이전트를 나눈 의미가 없다.

## 입력

- `{name}` — 도메인명
- **선행**: `backend/docs/domain/{name}-design.md` (설계 확정 상태) + `{name}.md` (요구 확정)

## 에이전트 역할 분담

| 에이전트 | 읽는 것 | 쓰는 것 | 안 하는 것 |
|---|---|---|---|
| [developer](../../agents/developer.md) | **설계서만** | `src/main/**` | 테스트 작성, 요구 문서 해석 |
| [tester](../../agents/tester.md) | 유스케이스 + 설계서 + [testing.md](../../../backend/docs/rule/testing.md) | `src/test/**` | 프로덕션 코드 수정 |
| [code-reviewer](../../agents/code-reviewer.md) | 변경된 코드 + 규칙 문서 | (읽기 전용) | 코드 수정 |

**두 에이전트는 서로를 직접 호출하지 않는다.** `backend/workspace/{name}/` 의 보고서로만 통신하고,
호출은 이 스킬이 한다 → [references/handoff.md](references/handoff.md)

## 참조

| 무엇이 필요할 때 | 어디를 |
|---|---|
| workspace 보고서 양식·원인 분류 | [references/handoff.md](references/handoff.md) |
| 테스트 패턴·커버리지 기준 | [testing.md](../../../backend/docs/rule/testing.md) |
| 모듈 경계 규칙 | [architecture.md](../../../backend/docs/rule/architecture.md) |

## 절차

### 0. 사전 검증

- backend 브랜치인지 확인
- `{name}-design.md` 존재 + **상태가 "설계 확정"** 인지. 확정 대기면 `/develop-design {name}` 안내 후 중단
- **대응 유스케이스 버전**이 `{name}.md` 와 일치하는지. 어긋나면 설계서 갱신이 먼저다
- §12 "결정 필요"에 미해결 항목이 있으면 → 사용자에게 확인받고 시작
- `backend/workspace/{name}/` 준비 (gitignore 대상)

### 1. 구현 — `developer`

```
Agent(subagent_type="developer",
      prompt="{name} 도메인을 backend/docs/domain/{name}-design.md 대로 구현. 라운드 1.")
```

### 2. 테스트 — `tester`

```
Agent(subagent_type="tester",
      prompt="{name} 도메인 테스트. 유스케이스 U-번호와 설계서 도메인 제약이 전부 덮였는지 확인하고
              빠진 테스트를 작성한 뒤 실행. 라운드 {n}.")
```

### 3. 루프 판정

테스터 판정에 따라 갈린다.

**통과** → 4단계로.

**실패** → `round-{n}-test.md` 를 읽고 다음 라운드를 연다.

```
Agent(subagent_type="developer",
      prompt="{name} 재구현. backend/workspace/{name}/round-{n}-test.md 의 수정 지시만 처리. 라운드 {n+1}.")
```
→ 다시 2단계.

**루프 상한과 중단 조건**

- **최대 5 라운드.** 넘으면 멈추고 남은 실패와 시도한 것을 사용자에게 보고한다.
- **같은 테스트가 2라운드 연속 같은 이유로 실패**하면 즉시 멈춘다 — 접근이 틀린 것이라
  라운드를 더 돌려도 같은 결과다. 무엇을 시도했고 왜 안 되는지 사용자에게 올린다.
- **`blockers.md` 가 생기면 즉시 멈춘다.** 설계로 판정 불가한 것을 추측으로 넘기면
  잘못된 구현 위에 테스트가 쌓인다. `/develop-design {name}` 으로 설계서를 보강하는 것이 정석이다.
- 라운드마다 **한 줄로 진행 상황을 사용자에게 알린다** (실패 n건 → n건, 커버리지 n%).
  루프가 조용히 5번 돌면 사용자는 무슨 일이 일어나는지 모른다.

### 4. 코드 리뷰 — `code-reviewer`

테스트가 전부 통과한 뒤에만 호출한다.

```
Agent(subagent_type="code-reviewer", prompt="{name} 도메인 구현 코드 리뷰. 변경 파일 대상.")
```

### 5. 결과 보고

리뷰 결과를 **사용자에게 그대로 출력**한다. 🔴 항목을 임의로 고치지 않는다 —
고칠지, 어떻게 고칠지는 사용자가 정한다.

```
## {name} 구현 완료

- 라운드: {n}회 (개발 {n} / 테스트 {n})
- 테스트: {통과}/{전체} · ModularityTest 통과
- 변경 파일 라인 커버리지: {n}%
- 신규 파일 {n}개 / 수정 {n}개

### 코드 리뷰 결과
{code-reviewer 출력 그대로}

### 다음
- 🔴 항목 처리 방향 확인 → 반영 시 `/implement {name}` 재실행 또는 직접 수정
- 커밋 → `/docs-sync` 로 마감
```

### 6. 커밋 (사용자 확인 후)

설계서 §11 체크리스트의 커밋 단위를 따른다 (`feat({name})` / `test({name})` / `chore(db)`).
**커밋은 이 스킬이 사용자 확인을 받고 한다** — 에이전트는 커밋하지 않는다.

## 성공 조건

- [ ] 유스케이스의 **U-번호가 전부 테스트로 검증**된다 (통과만이 아니라 존재)
- [ ] 설계서 §4 모듈 매핑의 산출물이 **전부** 그 위치에 있다
- [ ] `./gradlew check` 통과 — 테스트 + `ModularityTest` + 커버리지 게이트
- [ ] 이번 도메인 변경 파일의 라인 커버리지 100% (도달 불가 라인은 근거 명시)
- [ ] `code-reviewer` 결과가 사용자에게 **그대로** 전달됐다
- [ ] `blockers.md` 가 비어 있다 (또는 사용자가 인지하고 진행을 승인했다)
- [ ] `backend/workspace/` 가 커밋되지 않았다

## 안전장치

- **설계 확정 전 구현 금지** — 요구·설계가 흔들리면 코드와 테스트를 둘 다 다시 쓴다
- **에이전트 영역 침범 감시** — 라운드 후 `git status` 로 확인한다.
  개발자가 `src/test/**` 를, 테스터가 `src/main/**` 를 건드렸으면 **되돌리고 재지시**한다
  (분리가 무너지면 아무도 사양을 지키지 않게 된다)
- **커버리지 제외 추가 금지** — `coverageExclusions` 를 늘려 게이트를 통과시키지 않는다.
  정말 필요하면 사유와 함께 사용자 판단을 받는다
- **리뷰 지적을 자동 반영하지 않는다** — 🔴 도 사용자 확인 후
- **루프를 조용히 돌리지 않는다** — 라운드마다 진행 상황 보고

## 파이프라인 위치

```
/usecase {name}  →  /develop-design {name}
                              ▼
                      /implement {name}            [이 스킬]
                   ┌──────────┴──────────┐
                   │  developer         │ src/main
                   │      ↓ workspace     │
                   │  tester            │ src/test
                   │      ↓ (실패 시 반복) │
                   │  code-reviewer     │ 읽기 전용
                   └──────────┬──────────┘
                              ▼
                      /docs-sync  (마감)
```
