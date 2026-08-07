---
name: develop-design
description: "\"개발 설계\", \"도메인 설계\", \"설계 문서\", \"리팩터링 계획\", \"develop-design {name}\" 요청 시 활성화. 확정된 유스케이스 명세를 근거로 백엔드 소프트웨어 설계서를 backend/docs/domain/{name}-design.md 에 작성한다. 설계 문서까지만 만들고 코드는 작성하지 않는다. backend 브랜치 전용."
---

# develop-design — 소프트웨어 설계서 작성

확정된 요구(`{name}.md`)를 받아 **어떻게 구현할지**를 설계서로 굳힌다 → `backend/docs/domain/{name}-design.md`

**이 스킬은 코드를 작성하지 않는다.** 구현은 설계서 §4 모듈 매핑을 따라 별도 사이클이 담당한다.

## 입력

- `{name}` — 도메인/작업명 (소문자)
- 모드: **신규 개발** | **리팩터링** (사용자 의도에서 판단, 모호하면 질문)
- **선행 문서**: `backend/docs/domain/{name}.md` — 요구·정책·API 계약의 정본.
  설계서는 이것을 **중복 기술하지 않고 링크로 참조**한다.

## 참조 (필요할 때 읽는다)

| 무엇이 필요할 때 | 어디를 |
|---|---|
| 설계서 양식·작성 원칙·Modulith 용어 | [references/template.md](references/template.md) |
| 리팩터링 계획 양식 | [references/refactoring.md](references/refactoring.md) |
| 확정 문서를 고칠 때 (v2 표기) | [usecase/references/versioning.md](../usecase/references/versioning.md) |
| 모듈 구조·경계·이벤트 규칙 | [architecture.md](../../../backend/docs/rule/architecture.md) |
| 테스트 종류·커버리지 기준 | [testing.md](../../../backend/docs/rule/testing.md) |
| 에러 코드 규약 | [error-handling.md](../../../backend/docs/rule/error-handling.md) |
| 스니펫 네이밍·문서 파이프라인 | [rest-docs.md](../../../backend/docs/rule/rest-docs.md) |
| 토스 연동 지점 | [toss-integration.md](../../../backend/docs/rule/toss-integration.md) · `/toss-api` |
| 판단이 갈린 결정을 따로 남기고 싶을 때 | `/adr-create` → `backend/docs/adr/` |
| 기존 코드 선례 | `backend/src/main/java/kang20/ytcreator/` |

> **규칙 값을 이 문서에 옮겨 적지 않는다.** 커버리지 수치·패키지 경로·에러 네이밍은
> 위 규칙 문서가 정본이다. 설계서에도 값 대신 링크를 쓴다.

## 절차

### 0. 사전 검증
- backend 브랜치인지 확인
- **유스케이스 명세가 없거나 🔶 미확정이 남아 있으면** → `/usecase {name}` 선행 안내 후 중단
  (요구가 흔들리는 상태에서 설계하면 두 문서를 다시 고치게 된다)
- **유스케이스 버전을 읽는다.** 설계서가 이미 있고 버전이 어긋나면
  → 그 사실부터 보고하고 **버전 올림 모드**로 진입 ([versioning.md](../usecase/references/versioning.md))
- 설계서가 이미 있으면 → **덮어쓰기 금지**, 갱신/보강 모드로 사용자 확인

### 1. 선례 조사 (코드 기반)
유사 기능의 실제 코드를 읽는다 — 엔티티·서비스·컨트롤러·테스트.
**모듈 의존 그래프를 먼저 그려본다**: 두 모듈이 서로를 참조하면 `verify()` 가 깨지므로,
한쪽을 이벤트 구독으로 뒤집을 지점을 설계 단계에서 정한다.

선례가 없으면(도메인이 아직 없으면) 그 사실을 명시하고, 이 설계가 이후 선례가 된다는 전제로 쓴다.

### 2. 설계
모듈 경계·엔티티·매핑·핵심 흐름을 확정. 경쟁 쓰기가 있으면 전용 섹션(§6)으로 상세 설계.
기존 코드를 건드리면 리팩터링 표(§7)로 범위를 못박는다.

> **대안을 비교해 하나를 고른 지점**이 있으면 `/adr-create` 로 따로 기록할지 사용자에게 제안한다.
> 설계서는 "무엇을 어떻게"를 담고 구현이 끝나면 갱신되지만, ADR 은 **"왜 그렇게 정했나"** 를
> 그 시점 판단으로 동결해 남긴다. 기각안이 표 한 줄로 담기엔 아까운 논의가 그 대상이다.
> 대안이 하나뿐이었던 결정은 ADR 로 만들지 않는다 — 기각 카드가 없는 ADR 은 빈 껍데기다.

### 3. 작성
[references/template.md](references/template.md) (신규) 또는 [references/refactoring.md](references/refactoring.md) (리팩터링).

### 4. 검증 — `spec-verifier` 에이전트에 위임

문서를 다 쓰면 **사용자에게 보고하기 전에** 검증 에이전트를 호출한다.

```
Agent(subagent_type="spec-verifier",
      prompt="backend/docs/domain/{name}-design.md 를 develop-design 성공 조건으로 검증. {n}회차")
```

- **FAIL** → 수정 후 재호출(회차를 알린다). **최대 3회.**
- 같은 항목이 두 번 연속 FAIL 이면 수정 방향이 틀린 것이므로 사용자에게 묻는다.
- **⚠️ 확인 요청**·**판정 보류** 항목은 이 스킬이 직접 판단한다.

### 5. 보고 + 다음 단계
- 핵심 설계 결정(특히 트레이드오프)을 요약 보고하고 확인받는다
- 커밋: `docs({name}): 도메인 설계 문서 작성` / `docs({name}): 리팩터링 계획 작성`
- 신규: §4 매핑대로 **구현 착수** (골격 = 첫 `feat({name})` 커밋에 포함)
- 리팩터링: §5 단계별로 커밋, 각 단계 후 `./gradlew test` 로 그린 유지

## 성공 조건

아래를 **전부** 만족해야 끝난 것이다.
판정은 [spec-verifier](../../agents/spec-verifier.md) 가 독립 컨텍스트에서 대조한다.

- [ ] 유스케이스 명세의 **U-번호가 §5 또는 §10 에서 전부 커버**된다 (누락된 요구가 없다)
- [ ] 헤더의 **대응 유스케이스 버전**이 `{name}.md` 의 현재 버전과 일치한다
- [ ] 요구·정책·API JSON 을 **중복 기술하지 않았다** (링크로 참조)
- [ ] §4 모듈 매핑이 `internal/` 경계와 `allowedDependencies` 를 명시한다
- [ ] 모듈 간 통신이 **이벤트 우선**이고, 직접 의존은 이유와 함께 `allowedDependencies` 에 적혔다
- [ ] 헥사고날 용어(포트·어댑터·인바운드/아웃바운드)가 **하나도 없다**
- [ ] §3 에 **수동 DDL 필요 여부**가 판정돼 있다 (운영은 `ddl-auto: validate`)
- [ ] §10 에 **어떤 테스트가 어떤 라인을 덮는지**가 있고, 변경 파일 100% 기준이 명시됐다
- [ ] 경쟁 쓰기가 있으면 §6 이 있고, 없으면 §6 이 생략됐다 (억지로 채우지 않았다)
- [ ] 기존 파일을 건드리면 §7 에 **전수** 나열됐다 (삭제된 요구가 남긴 코드 포함)
- [ ] 커버리지 수치·패키지 경로를 **문서에 복사하지 않고 규칙 문서를 링크**했다
- [ ] 코드가 작성되지 않았다 (이 스킬은 문서까지)
- [ ] 커밋됨

## 안전장치

- **유스케이스 선행** — `{name}.md` 미확정 상태에서 설계하지 않는다
- **문서 기반·추측 금지** — 근거 없는 내용은 §12 "결정 필요"로 남긴다
- **설계서까지만** — 코드를 작성하지 않는다
- **기존 문서 덮어쓰기 금지**
- **버전 어긋남 방치 금지** — 대응 유스케이스 버전이 다르면 설계 전에 보고
- **규칙 정합성 self-check** — architecture/testing/error 규칙과 모순되면 수정
- **MVP 범위 우선** — 유스케이스의 백로그는 설계서에서도 범위 외

## 파이프라인 위치

```
/usecase {name}           요구 정본  docs/domain/{name}.md
   │  (🔶 전부 확정 후)
   ▼
/develop-design {name}    설계       docs/domain/{name}-design.md   [이 스킬]
   ▼
구현 (§4 매핑대로 골격 직접 생성, 변경 파일 100% 커버)
   ▼
/code-review → /docs-sync
```
