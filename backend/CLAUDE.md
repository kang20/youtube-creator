# CLAUDE.md — ytcreator 백엔드

> 경량 허브. 규칙 본문은 `docs/rule/*.md` 에 있고 여기서는 링크만 건다.

## 기술 스택

Spring Boot 4 · **Spring Modulith** · Java 25 · Gradle(Kotlin DSL) · JPA · Spring Security(stateless 익명키)
· Spring REST Docs · H2(test)/MySQL(운영)

## 핵심 원칙 (한 줄 요약 + 상세 링크)

- **아키텍처**: Spring Modulith — 패키지=모듈, 경계는 테스트가 강제 → [docs/rule/architecture.md](docs/rule/architecture.md)
- **테스트**: 커버리지 목표 100%(게이트 LINE 95/BRANCH 90), 컨트롤러 테스트 = REST Docs → [docs/rule/testing.md](docs/rule/testing.md)
- **API 문서**: Spring REST Docs, HTML 만 main push → [docs/rule/rest-docs.md](docs/rule/rest-docs.md)
- **에러 처리**: ErrorCode enum + BusinessException → [docs/rule/error-handling.md](docs/rule/error-handling.md)
- **배포/인프라**: OCI VM 2대 + 블루-그린 → [docs/deploy.md](docs/deploy.md), 스킬 `/infra`
- **모니터링**: 기존 모니터링 서버에 얹는다 → [monitoring/onboarding/README.md](monitoring/onboarding/README.md)

## 모듈 목록

<!-- TODO: 모듈이 생길 때마다 한 줄씩. `./gradlew test --tests "*ModularityTest"` 로 실제 구조 확인 -->

| 모듈 | 책임 | 허용 의존 |
|---|---|---|
| `shared` | 공용 예외·에러코드·감사시각·익명키 인증 (OPEN) | — |
| `config` | 전역 스프링 설정(보안·CORS·Clock·Auditing) (OPEN) | `shared` |

도메인 모듈은 아직 없다. 첫 모듈은 `/b-usecase` → `/b-develop-design` → 구현 순서로 만든다.

## 토스 인앱 MCP 활용

apps-in-toss MCP 로 플랫폼 문서를 조회한다. 검색은 **한국어 키워드 필수**.

- 검색 `search_docs` → 상세 `get_doc`. 구현 전 반드시 최신 스펙 확인 → `docs/platform/` 대조
- 자동화: `/b-toss-api`

## 개발 흐름

- 코드 작업은 **backend 브랜치**. push = 배포 트리거.
- 문서(`docs/api/` HTML, 기획 문서)는 **`/b-docs-sync` 스킬로만** main 에 push.
- 커밋은 역할별 분리: feat / test / refactor / fix / docs / chore (혼합 금지).
- 새 도메인: `/b-usecase {name}` → `/b-develop-design {name}` → 구현 → `/b-docs-sync`

## 규칙

- 한글로 대답
- MVP 범위만 집중, 과한 추상화 금지
- 테스트 없는 머지 금지 — 컨트롤러 테스트 = REST Docs
- 모듈 경계를 넘는 참조가 필요하면 **먼저 이벤트를 고려**한다
- 문서의 **🔶 표시는 미확정 결정** — 임의 확정 말고 사용자에게 확인
