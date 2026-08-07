---
name: git-commit
description: Git 커밋 수행. "커밋", "commit", "커밋해줘" 요청 시 활성화. 커밋 전 pull 강제, main은 .md 문서만, frontend/backend 영역 분리.
---

# git-commit (ytcreator 레포 규칙)

- **docs 최신화는 `main`에서** 각자 진행해요. 단 **`main`에는 문서(`.md`)·`assets/`만** 커밋/푸시할 수 있어요. (프론트/백엔드 코드는 금지)
- **코드 작업은 `frontend` / `backend` 브랜치**에서.
- **커밋하기 전에는 항상 `git pull`** 로 최신화해요.

## 🔒 강제 규칙 (위반 시 커밋 거부)

1. **커밋 전 pull 필수.** `origin/<현재 브랜치>` 보다 뒤처져 있으면 커밋이 거부돼요 → `git pull` 먼저.
2. **`main` = 문서 전용.** `.md` 파일만 커밋 가능(`.githooks/` 운영 파일만 예외). 코드·이미지 등 비-문서 파일은 거부돼요.
3. **브랜치–영역 분리.** `frontend` 브랜치는 `backend/` 변경 금지, `backend` 브랜치는 `frontend/` 변경 금지.
4. **커밋은 무조건 이 스킬을 거쳐서.** `git add -A`/`git add .` 금지(구체 파일명만), `--amend` 금지, 시크릿(`.env` 등) 제외.

> 위 규칙은 `.githooks/pre-commit`로 **실제 강제**됩니다.
> **클론 후 최초 1회** 반드시: `git config core.hooksPath .githooks`

## 트리거 키워드

`커밋`, `commit`, `커밋해줘`, `커밋 해줘`, `변경사항 저장`

## 커밋 워크플로우

### Step 0: 최신화 + 브랜치/영역 검증 (먼저!)

```bash
branch=$(git branch --show-current)
git pull --rebase --autostash origin "$branch"   # 커밋 전 항상 최신화
```

- `main` 이면: 변경 파일이 **전부 `.md`(또는 `.githooks/`)** 인지 확인. 코드/이미지가 섞였으면 중단하고 `frontend`/`backend` 브랜치로 안내.
- `frontend`/`backend` 이면: 반대 영역(`backend/` ↔ `frontend/`) 변경이 섞였는지 확인.

### Step 1: 현재 상태 확인 (병렬)

```bash
git status
git diff --staged
git diff
git log --oneline -5
```

### Step 2: 변경 내용 분석

- staged가 없으면 unstaged 확인 후 어떤 파일을 커밋할지 사용자에게 물어봅니다.
- 변경이 없으면 "커밋할 변경 사항이 없습니다"라고 알립니다.
- `.env`·시크릿이 포함되면 **경고**하고 제외합니다.

### Step 3: 커밋 메시지 작성

- 형식: `type(scope): summary` (기존 스타일 따름)
- 예시:
  ```
  docs(prd): 기간 세그먼트 랭킹 추가

  Co-Authored-By: 현재 사용 중인 에이전트명 <noreply@example.com>
  ```

### Step 4: 사용자 확인

- 커밋 메시지 초안을 보여주고 **반드시 사용자 확인**을 받습니다.

### Step 5: 커밋 실행

```bash
git add <specific-files>
git commit -m "$(cat <<'EOF'
type(scope): summary

Co-Authored-By: 현재 사용 중인 에이전트명 <noreply@example.com>
EOF
)"
git status
```

### Step 6: 푸시 (요청 시)

```bash
git push origin "$(git branch --show-current)"
```

## 주의 사항

- `git add -A` / `git add .` 금지 — 항상 **구체 파일명**.
- 기존 커밋 `--amend` 금지 — 항상 **새 커밋**.
- pre-commit hook 실패 시 메시지를 읽고 규칙에 맞게(특히 `git pull`) 처리한 뒤 다시 시도합니다. `--no-verify`로 우회하지 않습니다.
