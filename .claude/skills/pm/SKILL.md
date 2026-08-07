---
name: pm
description: "현재 개발 단계 진단 + 미흡한 작업 + 다음 할 일 가이드. \"지금 어디\", \"진척도\", \"다음 뭐\", \"pm\", \"상태 점검\" 요청 시 활성화. backend 브랜치 전용."
---

# pm — 개발 진척 진단 + 다음 단계 가이드

> 파이프라인(계획 → 골격 → 구현 → 테스트 → 리뷰 → 문서배포) 위에서
> **지금 어느 단계인지, 무엇이 미흡한지, 다음에 뭘 할지**를 진단해 보고한다.
> 이 스킬은 **읽기·진단 전용** — 코드/문서를 수정하지 않고 가이드만 한다.

## 트리거 키워드

`지금 어디`, `진척도`, `다음 뭐`, `상태 점검`, `pm`, `현황`, `어디까지 했지`, `할일`

## 입력

- `{name}` (선택): 특정 도메인만 진단. 없으면 **전체 도메인 + 전역 인프라**를 스캔.

## 파이프라인 단계 정의 (단일 기준)

도메인 1개의 정형 사이클. usecase/develop-design/docs-sync 와 동일.

| # | 단계 | 산출물(탐지 신호) | 담당 스킬 |
|---|------|------------------|-----------|
| 0 | **Phase 1 뼈대** | `shared/`, `config/`, `ModularityTest`, `docs/rule/*.md`, `CLAUDE.md` | (수동) |
| 1 | **유스케이스+설계** | `{name}.md`(유스케이스)+`{name}-design.md`(설계) — poll 이전 레거시는 단일 파일 | `/usecase` → `/develop-design` |
| 2 | **골격 생성** | `{name}/package-info.java`(@ApplicationModule) + `{name}/internal/` | (수동 — 설계서 §4 매핑대로 첫 feat 커밋에 포함) |
| 3 | **엔티티+Repository** | `{name}/internal/{Name}.java` 본문 有, `internal/{Name}Repository` | (수동) |
| 4 | **Service(모듈 공개 API)** | 모듈 루트 `{Name}Service` 로직 有, 필요 시 `{Name}Api` 인터페이스 | (수동) |
| 5 | **Controller+DTO** | `{name}/{Name}Controller`, `{name}/dto/` 본문 有 | (수동) |
| 6 | **테스트+RESTDocs** | `{Name}ControllerTest` 통과, `build/generated-snippets/{name}-*`, 변경 파일 100% | (수동 — `./gradlew test`) |
| 7 | **코드 리뷰** | 리뷰 반영 커밋(`refactor({name})`) | `/code-review --fix` |
| 8 | **보안 리뷰** | (인증·결제 도메인만) | `/security-review` |
| 9 | **마감(테스트 리포트+문서+push)** | 커버리지 리포트 + `docs/api/` HTML + main 반영 | `/docs-sync` |

## 동작 흐름

### Step 0: 사전 검증

```bash
git branch --show-current   # backend 브랜치 확인
```

backend 브랜치가 아니면 경고(코드 진척 진단은 backend 기준).

### Step 1: 전역(Phase 1) 상태 스캔

뼈대가 갖춰졌는지 먼저 확인한다(도메인 진단의 전제).

```bash
ls backend/docs/rule/                                   # 규칙 문서 5종
ls backend/src/main/java/ytcreator/backend/common/       # 공유 인프라
ls backend/src/main/java/ytcreator/backend/config/       # Spring 설정
ls backend/src/test/java/ytcreator/backend/base/         # 테스트 베이스
```

- 누락된 뼈대가 있으면 → "Phase 1 미완" 으로 보고하고 그것부터 안내.

### Step 2: 도메인 목록 수집

```bash
ls backend/docs/domain/                        # 계획된 모듈(설계 문서)
ls backend/src/main/java/kang20/ytcreator/   # 구현된 애플리케이션 모듈 = 최상위 패키지 목록
# 모듈 구조를 코드가 아니라 Modulith 에게 직접 묻는 방법(가장 정확):
cd backend && ./gradlew test --tests "*ModularityTest" 
```

- 두 목록의 **차집합**이 곧 단계 판정의 핵심:
  - 설계만 있고 골격 없음 → 1단계 완료, 2단계 대기
  - 골격만 있고 설계 없음 → **역행(경고)**: `/develop-design` 로 설계서부터 보강

### Step 3: 도메인별 단계 판정

각 도메인에 대해 위 "탐지 신호"를 순서대로 확인해 **도달 단계**를 정한다.

```bash
# 예: vote 모듈
V=backend/src/main/java/kang20/ytcreator/vote
ls $V/package-info.java        # @ApplicationModule 선언 = 모듈로 인정됨
ls $V/internal/                # 엔티티·리포지토리(모듈 밖 접근 불가)
ls $V/*Service.java $V/*Api.java 2>/dev/null   # 모듈 공개 API
ls $V/*Controller.java
ls backend/build/generated-snippets/ | grep '^vote-'   # RESTDocs 스니펫
git log --oneline -- $V               # 커밋 이력으로 리뷰/문서 단계 추정
```

판정 규칙:
- 파일이 **존재하지만 비어있거나 TODO 골격**이면 그 단계는 "미흡(in-progress)" 으로 분류.
- 스니펫이 없는데 컨트롤러가 있으면 → **6단계 미흡**(테스트/문서 누락) 경고.
- `docs/api/` 변경이 backend 미커밋이면 → **9단계(마감) 미흡**.

### Step 4: 진척 보고 (핵심 산출물)

아래 형식으로 보고한다.

```
## 📍 현재 위치

- 브랜치: backend
- Phase 1 뼈대: ✅ 완료 / ⚠️ 미완({누락 항목})

## 도메인별 진척도

| 도메인 | 도달 단계 | 상태 | 미흡한 점 |
|--------|----------|------|----------|
| auth   | 6/9     | 🟡   | 보안 리뷰(8) 미실시, docs/api 미배포 |
| vote   | 2/9     | 🟡   | 엔티티 본문 없음(3단계 대기) |
| ranking| 1/9     | ⚪   | 골격 미생성 → 설계서 매핑대로 구현 착수 |

## ⏭️ 다음 할 일 (우선순위)

1. **auth** — `/security-review` (인증 도메인 필수) → /docs-sync
2. **vote** — 엔티티 + Repository 구현 (3단계)
3. **ranking** — 설계서(§모듈 매핑)대로 골격 직접 생성(구현 착수)

## ⚠️ 주의 / 역행
- {설계 없이 골격만 있는 도메인 등 경고}
```

### Step 5: 다음 액션 1개 제안

가장 우선순위 높은 **단 하나의 다음 명령**을 명확히 제시한다(과잉 제안 금지).

```
👉 다음 추천: /security-review (auth 도메인)
   이유: 구현·테스트는 끝났으나 인증 도메인 보안 점검이 파이프라인상 필수.
```

## 단계 판정 휴리스틱 (요약)

| 신호 | 해석 |
|------|------|
| `docs/domain/{name}.md` 無 | 1단계 미시작 → `/develop-design {name}` |
| `domain/{name}/` 無 | 2단계 미시작 → 설계서 §모듈 매핑대로 구현 착수 |
| entity 파일만 골격(필드 없음) | 3단계 미흡 |
| service에 로직 없음(throw 골격) | 4단계 미흡 |
| controller 무 / dto 무 | 5단계 미흡 |
| 컨트롤러 有 + 스니펫 無 | 6단계 미흡(테스트/문서 누락) |
| 인증·결제 도메인 + 보안리뷰 흔적 無 | 8단계 미흡 |
| `docs/api/` 변경 backend 미커밋 / main 미반영 | 9단계(마감) 미흡 → `/docs-sync` |

> 우선순위는 [develop-design 권장 순서](../develop-design/SKILL.md)(auth → candidate → vote+ballot → ranking → favorite/cheer → order → notification)를 따른다.

## 안전장치

- **읽기 전용** — 이 스킬은 코드/문서/git 상태를 변경하지 않는다. 진단·가이드만.
- **추측 최소화** — 파일 존재/내용으로 판정하고, 불확실하면 "확인 필요"로 표기(임의 단정 금지).
- **다음 액션은 1개** — 한 번에 하나의 명확한 다음 단계만 추천(혼란 방지).
- **역행 감지** — 설계 없이 구현이 앞서간 경우 경고하고 계획서 보강을 우선 안내.

## 다른 스킬과의 관계

```
/pm  ← 진단·가이드(읽기 전용) [이 스킬]
  │ "다음은 이거" 제안
  ▼
/usecase → /develop-design → 구현 → /code-review → /docs-sync
   → /security-review → /docs-sync
```
