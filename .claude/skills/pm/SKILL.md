---
name: pm
description: "현재 개발 단계 진단 + 미흡한 작업 + 다음 할 일 가이드. \"지금 어디\", \"진척도\", \"다음 뭐\", \"pm\", \"상태 점검\" 요청 시 활성화. backend 브랜치 전용."
---

# pm — 개발 진척 진단 + 다음 단계 가이드

> 파이프라인 위에서 **지금 어느 단계인지, 무엇이 미흡한지, 다음에 뭘 할지**를 진단해 보고한다.
> 이 스킬은 **읽기·진단 전용** — 코드도 문서도 고치지 않는다. 고치는 건 각 단계의 스킬 몫이다.

## 트리거 키워드

`지금 어디`, `진척도`, `다음 뭐`, `상태 점검`, `pm`, `현황`, `어디까지 했지`, `할일`

## 입력

- `{name}` (선택): 특정 도메인만 진단. 없으면 **전체 도메인 + 전역 뼈대**를 스캔.

## 파이프라인 (단일 기준)

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
```

**정본은 한 파일이다.** 요구·참고자료·용어·모델이 `backend/docs/new-domain/{name}/{name}-v{n}.md` 하나에
쌓인다. 진단은 그 파일의 **섹션 존재 여부와 상태 헤더 문자열**로 한다 — 파일이 여러 개였을 때처럼
문서 개수를 세지 않는다.

**요구 섹션은 상태 헤더 다음의 첫 번째 `## ` 섹션이다. 제목 문자열로 찾지 않는다.**
제목은 `## {도메인명} 도메인` 형식이고 `{도메인명}` 은 한글이어도 영문이어도 된다
(`## 결제 도메인` 이 정상이다). 이름으로 grep 하면 도메인마다 깨진다.

기호: `{n}`=정의서 버전 · `{k}`=리뷰 회차 · `{r}`=내부 라운드 · `{v}`=spec-verifier 검증 회차.

## 단계 정의 (0~8)

| # | 단계 | 완료 신호(탐지) | 담당 |
|---|------|----------------|------|
| 0 | **전역 뼈대** | `shared/` · `config/` · `ModularityTest` · `docs/rule/*.md` 5종이 모두 있다 | (수동) |
| 1 | **요구** | `new-domain/{name}/{name}-v{n}.md` 에 **요구 섹션**(상태 헤더 다음 첫 `## ` 섹션)이 있고, 상태 헤더가 `요구 확정({날짜})` 이상이며 **요구 🔶 0건** | `/usecase {name}` |
| 2 | **보강** *(선택)* | `## 참고자료 — {제목}` 또는 `## 용어 사전` 이 있다 | `/usecase {name} {정보}` · `/usecase 용어 {name}` |
| 3 | **도메인 모델** | 같은 파일에 `## 도메인 모델` 이 있고, 상태 헤더가 `설계 확정({날짜})` **이상**(`구현 완료({날짜})` 포함)이며 **모델 🔶 0건** | `/develop-design {name}` |
| 4 | **구현** | `{name}/` 패키지에 산출물이 있고 `./gradlew test` 통과 + **변경 파일 커버리지 100% 수렴** (상태 헤더가 `구현 완료({날짜})` 면 이 단계가 닫혔다는 신호다) | `/implement {name}` |
| 5 | **코드 리뷰** | `backend/workspace/{name}/v{n}/` 의 **마지막 `review-{k}.md`** 마지막 줄이 `## 판정: PASS` | `/implement` 내부 |
| 6 | **보안 리뷰** | (인증·결제 도메인만) 보안 점검 흔적 | `/security-review` |
| 7 | **마감** | 커버리지 리포트 + `docs/api/` HTML + main 반영. 단 **HTTP 컨트롤러가 없는 모듈(이벤트 구독 전용 등)은 `docs/api/` 부분이 N/A** 다 | `/docs-sync` |
| 8 | **모델 시각화** | `backend/docs/model/master.md` 에 이 모듈 구역이 반영돼 있다. 단 **엔티티도 집계도 없어 `/domain-model` 이 '그릴 것 없음' 으로 판정한 모듈은 N/A** 다 | `/domain-model {module}` |

- **2단계는 선택이다.** 용어 사전과 참고자료가 없어도 3단계로 갈 수 있다. 다만 요구 섹션의
  [대괄호] 개념이 여럿인데 용어 사전이 없으면 **코드 식별자의 계약이 없는 상태**이므로 권고로 보고한다.
- 1·3단계의 완료 기준은 파일 존재가 아니라 **🔶 0건**이다. 🔶 가 남은 채로 다음 단계를 열면
  구현 중에 설계가 흔들린다.
- **상태 헤더는 단조 증가한다** — `요구 확정` → `설계 확정` → `구현 완료`. 그래서 완료 신호는
  "그 값이거나 **그보다 뒤**"로 읽는다. `구현 완료({날짜})` 인 정의서를 보고 3단계 미흡이라고
  적지 않는다. 이미 4단계까지 닫힌 도메인이다.
- 4단계와 5단계는 `/implement` 한 번에 함께 돈다. 5단계가 RETRY 로 끝났으면 **4단계 미흡**으로 본다.
- `review-{k}.md` 는 **PASS·RETRY 무관하게 남는다.** 그래서 5단계 완료 판정은 파일 존재가 아니라
  **`{k}` 가 가장 큰 파일의 마지막 줄**로 한다 — 그 줄은 반드시 `## 판정: PASS` 또는
  `## 판정: RETRY ({n}건)` 이다(`{n}` 은 🔴 항목 수). `PASS` 면 완료, `RETRY` 면 미흡이다.
  중간 회차의 RETRY 파일만 보고 미흡으로 단정하지 않는다.
- **판정은 워크스페이스의 최신 정의서 버전 디렉토리에서만 읽는다.** 워크스페이스는
  `backend/workspace/{name}/v{n}/` 로 **정의서 버전마다 갈린다** — `v1/` 이 PASS 로 끝났어도
  정의서가 v2 로 올랐으면 `v2/` 가 현재 진척이다. 옛 버전의 PASS 를 현재 판정으로 쓰지 않는다.
- `review-{k}.md` 가 하나도 없으면 그건 "리뷰 안 함"이 아니라 **판정 근거 없음**이다
  (`backend/workspace/` 는 `.gitignore` 대상이다) → 확인 필요로 표기한다.

## 레거시 도메인 — 진단 대상이 아니다

`backend/docs/domain/` 의 5개(`auth.md` · `auth-design.md` · `payment.md` · `payment-design.md` ·
`subtitle.md`)는 **구 양식 레거시(읽기 전용)** 다.

- 이 도메인들은 **코드가 정본**이다. 문서 양식이 새 파이프라인과 다르다는 이유로 역행 경고를 내지 않는다.
- 새 도메인은 `backend/docs/new-domain/` 에만 쓴다. 레거시를 삭제·이동·재작성하지 않는다.
- 보고 표에는 `구 양식 — 코드가 정본` 으로 표기하고 단계 숫자를 매기지 않는다.

## 동작 흐름

### Step 0: 사전 검증

```bash
git branch --show-current   # backend 아니면 경고
```

backend 브랜치가 아니면 경고한다(코드 진척 진단은 backend 기준이다).

### Step 1: 전역 뼈대 스캔

뼈대가 갖춰졌는지 먼저 확인한다 — 도메인 진단의 전제다.

```bash
ls backend/docs/rule/                                  # 규칙 문서 5종
ls backend/src/main/java/kang20/ytcreator/shared/      # 공용 예외·에러코드·타입 ID
ls backend/src/main/java/kang20/ytcreator/config/      # 전역 스프링 설정
ls backend/src/test/java/kang20/ytcreator/base/        # 테스트 베이스
ls backend/src/test/java/kang20/ytcreator/ModularityTest.java
```

- 누락이 있으면 **"0단계 미완"** 으로 보고하고 그것부터 안내한다. 뼈대가 없으면 도메인 단계 판정이 무의미하다.

### Step 2: 도메인 목록 수집

```bash
ls backend/docs/new-domain/                   # 도메인 폴더 목록 (신규 파이프라인)
ls backend/docs/new-domain/*/                 # 각 도메인의 정의서·ADR·다이어그램
ls backend/docs/domain/                       # 레거시 — 단계 판정 대상 아님
ls backend/src/main/java/kang20/ytcreator/    # 구현된 Modulith 모듈
cd backend && ./gradlew test --tests "*ModularityTest"   # 모듈 구조를 Modulith 에게 직접 묻는다
```

- 두 목록의 **차집합**이 판정의 핵심이다.
  - 정의서만 있고 패키지 없음 → 1~3단계 어딘가, 구현 대기
  - 패키지만 있고 정의서 없음 → **역행(경고)**: `/usecase {name}` 로 요구부터 세운다
  - 단, 레거시 3종(`auth` · `payment` · `subtitle`)은 이 경고에서 제외한다

### Step 3: 도메인별 단계 판정

정의서 한 파일만 읽으면 1~3단계가 전부 나온다.

```bash
D=$(ls backend/docs/new-domain/{name}/{name}-v*.md | tail -1)   # {n} 이 가장 큰 것이 현재 정의서다
head -5 "$D"                     # 상태 헤더 — 버전·상태·🔶 건수
grep -n '^## ' "$D"              # 섹션 목록 — 첫 줄이 곧 요구 섹션이다
grep -n -A99 '^### 요구 🔶' "$D"  # 남은 요구 🔶
grep -n -A99 '^### 모델 🔶' "$D"  # 남은 모델 🔶
```

**요구 섹션은 상태 헤더 다음의 첫 번째 `## ` 섹션이다. 제목 문자열로 찾지 않는다.**
위 `grep -n '^## '` 결과의 **맨 첫 줄**이 요구 섹션이다 — 제목이 `## 결제 도메인` 이든
`## payment 도메인` 이든 판정은 같다.

4단계 이후는 코드와 빌드 산출물로 본다.

```bash
V=backend/src/main/java/kang20/ytcreator/{name}
ls $V/package-info.java                                # @ApplicationModule = 모듈로 인정됨
ls $V/internal/                                        # 모듈 밖에서 못 보는 구현
ls backend/src/test/java/kang20/ytcreator/{name}/      # 테스트
ls backend/build/generated-snippets/ | grep '^{name}-' # REST Docs 스니펫

ls -d backend/workspace/{name}/v*/    # 정의서 버전별 작업 트리 — v{n} 이 가장 큰 것이 현재다
W=$(ls -d backend/workspace/{name}/v*/ | tail -1)
ls $W/requirements.md                 # tester 가 만든 REQ 체크리스트 (이 버전 안에서 하나만 유지)
ls $W/review*-round*-test.md          # tester → developer (review{k}-round{r}-test.md)
ls $W/review*-round*-dev.md           # developer → tester (review{k}-round{r}-dev.md)
ls $W/review-*.md                     # code-reviewer 판정 (review-{k}.md — PASS 도 남는다)
for f in $W/review-*.md; do echo "$f: $(tail -1 "$f")"; done   # 각 회차의 마지막 줄 = 판정
ls $W/blockers.md                     # 사용자 개입이 필요해 멈춘 항목

grep -n '{name}' backend/docs/model/master.md          # 8단계 반영 여부
```

- 워크스페이스는 `backend/workspace/{name}/v{n}/` 다. **`v{n}` 은 정의서 버전**이고
  정의서가 v2 로 오르면 `v2/` 가 새로 생기며 `k`·`r` 은 1 부터 다시 센다. `v1/` 은 그대로 남는다 —
  **판정은 `{n}` 이 가장 큰 디렉토리에서만 읽는다.**
- 파일명의 `{k}` 는 **리뷰 회차**(1..3), `{r}` 은 **내부 라운드**(1..5)다. `{k}`·`{r}` 이 클수록 최신이다.
- `review*-round*-*.md` 가 여러 개인 것은 정상이다 — 덮어쓰지 않고 쌓이는 구조라서
  **몇 회차 몇 라운드까지 갔는지**가 그대로 진척 신호가 된다.
- **판정 줄은 `review-{k}.md` 의 마지막 줄이다.** 본문 중간의 `## 판정:` 을 잡지 않도록
  `grep` 이 아니라 `tail -1` 로 읽는다. 값은 `## 판정: PASS` 또는 `## 판정: RETRY ({n}건)` 둘뿐이다.

판정 규칙:

- 파일이 있어도 **본문이 비었거나 TODO 골격**이면 그 단계는 "미흡(in-progress)" 이다.
- 상태 헤더와 섹션이 어긋나면 **헤더가 아니라 섹션을 믿는다**. 헤더 갱신은 사람이 빠뜨리기 쉽다.
- 상태 헤더 값은 `요구 초안` · `요구 확정({날짜})` · `모델 초안` · `설계 확정({날짜})` ·
  `구현 완료({날짜})` 다섯뿐이고 이 순서로 나아간다. `구현 완료({날짜})` 는 **3단계까지 전부
  닫혔다는 뜻**이므로 1·3단계 완료 판정에 그대로 쓴다.
- `requirements.md` 의 `상태` 열에 미충족 항목이 남아 있으면 → **4단계 미흡**.
  요구 번호는 `REQ-{n}` 이다 — 미충족 항목은 **`REQ-{n}` 번호를 그대로 인용해** 보고한다.
  (아키텍처 규칙 `R1`~`R7` 과 헷갈리지 마라. 그쪽을 인용할 때는 `architecture.md R1` 처럼 문서명을 붙인다.)
- 컨트롤러가 있는데 스니펫이 없으면 → **4단계 미흡**(테스트·문서 누락).
- 최신 `v{n}/` 안에서 `{k}` 가 가장 큰 `review-{k}.md` 의 **마지막 줄**이 `## 판정: RETRY ({n}건)` 이면
  → **4단계 미흡**(리뷰 지적 미반영). `## 판정: PASS` 면 5단계 완료다 —
  PASS 도 파일로 남으므로 파일 개수로 판정하지 않는다.
- `backend/workspace/` 는 `.gitignore` 대상이라 **없을 수도 있다.** 없는 것은 "구현 안 함"이 아니라
  "판정 근거 없음"이므로 확인 필요로 표기한다.
- `docs/api/` 변경이 main 에 미반영이면 → **7단계 미흡**.

### Step 4: 진척 보고 (핵심 산출물)

```
## 📍 현재 위치

- 브랜치: backend
- 전역 뼈대(0단계): ✅ 완료 / ⚠️ 미완({누락 항목})

## 도메인별 진척도

| 도메인 | 도달 단계 | 상태 | 미흡한 점 |
|--------|----------|------|----------|
| payment | 3/8 | 🟡 | 모델 🔶 1건(만료 완충 이름) — 확정 전엔 /implement 금지 |
| credit  | 5/8 | 🟡 | 보안 리뷰(6) 미실시, docs/api 미배포 |
| subtitle| — | ⚪ | 정의서 없음 → /usecase subtitle 로 요구부터 |

## 📎 레거시 (구 양식 — 코드가 정본)

| 도메인 | 문서 | 비고 |
|--------|------|------|
| auth | docs/domain/auth.md + auth-design.md | 재작성하지 않는다 |
| payment | docs/domain/payment.md + payment-design.md | new-domain/payment/payment-v2.md 로 이관 진행 중 |

## ⏭️ 다음 할 일 (우선순위)

1. **payment** — 모델 🔶 1건 확정 후 `/implement payment`
2. **credit** — `/security-review` (결제 인접 도메인)

## ⚠️ 주의 / 역행
- {정의서 없이 패키지만 있는 도메인 등}
```

### Step 5: 다음 액션 1개 제안

가장 우선순위 높은 **단 하나의 다음 명령**만 제시한다. 여러 개를 늘어놓으면 사용자가 고르느라 멈춘다.

```
👉 다음 추천: /develop-design payment
   이유: 요구 🔶 0건으로 1단계가 닫혔고, '## 도메인 모델' 섹션이 아직 없다.
```

## 단계 판정 휴리스틱 (요약)

| 신호 | 해석 |
|------|------|
| `new-domain/{name}/{name}-v*.md` 無 | 1단계 미시작 → `/usecase {name}` |
| 요구 섹션(첫 `## ` 섹션) 有 + 요구 🔶 남음 | 1단계 미흡 — 🔶 확정이 먼저다 |
| 요구 🔶 0건 + `## 도메인 모델` 無 | 3단계 대기 → `/develop-design {name}` |
| `## 도메인 모델` 有 + 모델 🔶 남음 | 3단계 미흡 — 확정 전 구현 금지 |
| 모델 🔶 0건 + `{name}/` 패키지 無 | 4단계 대기 → `/implement {name}` |
| 상태 헤더가 `구현 완료({날짜})` | 1·3단계 완료 + 4단계 닫힘 — 미흡으로 적지 않는다 |
| 패키지 有 + 스니펫 無 | 4단계 미흡(테스트·문서 누락). 단 **컨트롤러가 없는 모듈은 N/A** |
| 최신 `v{n}/` 의 마지막 `review-{k}.md` 끝줄이 `## 판정: RETRY ({n}건)` | 4단계 미흡(리뷰 지적 미반영) |
| 최신 `v{n}/` 의 마지막 `review-{k}.md` 끝줄이 `## 판정: PASS` | 5단계 완료 |
| `backend/workspace/{name}/v{n}/` 자체가 없음 | 판정 근거 없음 — 확인 필요(미완이라 단정하지 않는다) |
| 정의서는 `v2` 인데 워크스페이스에 `v1/` 뿐 | v2 기준 구현 미시작 — v1 의 PASS 를 현재 판정으로 쓰지 않는다 |
| 인증·결제 도메인 + 보안 리뷰 흔적 無 | 6단계 미흡 |
| `docs/api/` 변경 main 미반영 | 7단계 미흡 → `/docs-sync` |
| `master.md` 에 이 모듈 구역 無 | 8단계 미흡 → `/domain-model {module}`. 단 **엔티티도 집계도 없어 `/domain-model` 이 '그릴 것 없음' 으로 판정한 모듈은 N/A** 다 |
| `docs/domain/*.md` 만 있는 도메인 | 레거시 — 단계 판정 대상 아님 |

## 안전장치

- **읽기 전용** — 코드·문서·git 상태를 바꾸지 않는다. 진단과 가이드만 한다.
- **추측 최소화** — 파일 존재와 내용으로만 판정하고, 불확실하면 "확인 필요"로 적는다. 임의 단정은 잘못된 다음 액션을 부른다.
- **🔶 를 대신 확정하지 않는다** — 남은 🔶 는 건수와 위치만 보고한다(CLAUDE.md 규칙).
- **다음 액션은 1개** — 한 번에 하나만 추천한다.
- **역행 감지** — 정의서 없이 구현이 앞서갔으면 경고하고 요구 보강을 먼저 안내한다.
- **레거시에 역행 경고를 내지 않는다** — 구 양식은 이미 코드가 정본이라 문서를 되돌릴 일이 없다.

## 다른 스킬과의 관계

```
/pm  ← 진단·가이드(읽기 전용) [이 스킬]
  │ "다음은 이거" 제안
  ▼
/usecase → /develop-design → /implement → /security-review → /docs-sync → /domain-model
```
