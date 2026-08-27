# REST Docs 규칙

> 단일 진실원천. CLAUDE.md·스킬·도메인 문서가 이 문서를 링크한다.

## 핵심 철학

```
테스트가 곧 문서다 — 테스트 없이 문서 없고, 문서 없이 프론트 작업 없다.
```

컨트롤러 테스트가 통과하면 `build/generated-snippets/` 에 스니펫이 생기고,
asciidoctor 가 이를 모아 HTML 을 만든다. 테스트가 깨지면 문서도 없다.

## 파일 구조

| 파일 | 위치 | 역할 |
|------|------|------|
| `index.adoc` | `src/docs/asciidoc/` | 진입점. 도메인 adoc 들을 include |
| `common.adoc` | `src/docs/asciidoc/` | Base URL·인증·에러·시간대 공통 규격 |
| `{도메인}.adoc` | `src/docs/asciidoc/` | 도메인별 API (구현 시 추가, index 에 include) |

## adoc 소스 vs HTML 산출물 (역할 분리)

| 파일 | 위치 | 성격 | backend 커밋 | origin push |
|------|------|------|--------------|-------------|
| `.adoc` 소스 | `src/docs/asciidoc/` | 백엔드 **소스 코드** (스니펫 의존) | O | ❌ |
| `.html` 산출물 | `docs/api/` (레포 루트) | 빌드된 **완성 문서** | **O** | ✅ main (`/docs-sync`) |

> `.adoc` 은 "코드"이므로 origin 에 push 하지 않는다.
> `.html` 은 backend 브랜치에도 **커밋한다** — `/docs-sync` 가 `git checkout backend -- docs/api/` 로 가져오려면 backend 에 커밋돼 있어야 한다.
> main 은 "읽기용 완성 문서 저장소".

## 빌드 설정 (build.gradle.kts)

```kotlin
tasks.asciidoctor {
    inputs.dir(snippetsDir)        // build/generated-snippets
    dependsOn(tasks.test)          // 테스트 먼저
    setOutputDir(file("../docs/api"))   // HTML 출력만 레포 루트로
    baseDirFollowsSourceDir()
}
```

`./gradlew asciidoctor` → `src/docs/asciidoc/index.adoc` → `docs/api/index.html`.

## 스니펫 식별자 네이밍

```
{도메인}-{행위}
```

예) `vote-cast`, `vote-cast-fail-insufficient`, `ranking-list`.
성공/실패 케이스를 모두 별도 식별자로 문서화한다.

## 컨트롤러 테스트 작성 패턴

- `ControllerTest` 상속 (→ [testing.md](testing.md))
- `mockMvc.perform(...).andExpect(...).andDo(document("{식별자}", ...))`
- 요청/응답 필드, 헤더, 파라미터를 `requestFields`/`responseFields`/`requestHeaders` 로 기술
- 성공 + 실패(검증 오류, 권한 없음 등) 모두 작성

## 공통 문서 규격 (common.adoc)

- Base URL
- 인증 헤더: `Authorization: Bearer {accessToken}`
- 공통 에러 응답: `{ code, message }` (→ [error-handling.md](error-handling.md))
- 시간대: UTC ISO-8601

## 배포 파이프라인

```
[backend 브랜치]
  컨트롤러 테스트 → build/generated-snippets/ (git 무시)
  src/docs/asciidoc/*.adoc (소스, backend 커밋 / origin push X)
  ./gradlew asciidoctor → docs/api/index.html
  → docs/api/ HTML 도 backend 브랜치에 커밋 (origin push X)
     commit: docs(api): {name} API 명세 갱신

  ▼ /docs-sync

[main 브랜치]
  git checkout backend -- docs/api/
  → docs/api/index.html 만 main 에 커밋 + push
```

## pre-commit hook

main 브랜치에서 `docs/api/` 경로를 허용한다(`.githooks/pre-commit`).

```bash
nonmd="$(printf '%s\n' "$files" | grep -vE '(\.md$|^\.githooks/|^docs/api/|^\.gitignore$)' || true)"
```
