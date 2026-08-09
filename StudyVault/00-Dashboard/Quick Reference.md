---
module: dashboard
path: 00-Dashboard
keywords: quick-reference, commands, modulith-cheatsheet
---

# Quick Reference

#dashboard #quick-reference #arch-modulith

## 주요 명령

| 목적            | 명령                                         |
| ------------- | ------------------------------------------ |
| 모듈 경계만 빠르게    | `./gradlew test --tests "*ModularityTest"` |
| 전체 + 커버리지 게이트 | `./gradlew check`                          |
| 특정 모듈 테스트     | `./gradlew test --tests "*Auth*"`          |
| API 문서 HTML   | `./gradlew asciidoctor`                    |
| 런타임 모듈 구조     | `curl localhost:8080/actuator/modulith`    |

```bash
cd backend
./gradlew check
```

→ 상세는 [[빌드와 검증 파이프라인]]

## Modulith 어노테이션 치트시트

| 어노테이션 | 위치 | 효과 |
|---|---|---|
| `@ApplicationModule(displayName, allowedDependencies)` | `package-info.java` | 모듈 선언 + **의존 화이트리스트** |
| `@ApplicationModule(type = Type.OPEN)` | `package-info.java` | 하위 패키지까지 공개 (공유 커널용) |
| `@ApplicationModuleListener` | 구독 메서드 | `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener` |
| `@ApplicationModuleTest(mode = ...)` | 테스트 클래스 | 해당 모듈만 부팅 |
| `@NamedInterface` | 하위 패키지 | 모듈의 **일부만** 노출 |

→ 개념은 [[Spring Modulith 아키텍처]] · [[모듈 간 통신 — 이벤트 우선]]

## 판단 기준 3줄

| 상황 | 답 |
|---|---|
| 다른 모듈의 데이터가 필요하다 | **먼저 이벤트를 고려한다.** 동기 응답이 꼭 필요할 때만 직접 호출 + `allowedDependencies` 명시 |
| 두 모듈이 서로를 참조한다 | 순환이다. **한쪽을 이벤트 구독으로 뒤집거나** 제3의 집계 모듈을 둔다 |
| 엔티티를 다른 모듈에서 쓰고 싶다 | **경계 설계 실패 신호.** 필요한 값만 담은 record 를 모듈 루트에 노출한다 |

→ [[모듈 간 통신 — 이벤트 우선]]

## 중요 파일 위치

| 경로 | 용도 |
|---|---|
| `backend/src/main/java/kang20/ytcreator/*/package-info.java` | 모듈 선언 — **여기가 경계의 정본** |
| `backend/src/test/java/kang20/ytcreator/ModularityTest.java` | 경계 검증 (프로젝트당 1개) |
| `backend/src/test/java/kang20/ytcreator/auth/AuthModuleBoundaryTest.java` | `verify()` 가 **못 잡는** 불변식 |
| `backend/deploy/sql/` | 수동 DDL — 배포보다 **먼저** |
| `backend/docs/rule/architecture.md` | 구조 규칙 정본 |
| `backend/build/spring-modulith-docs/` | 자동 생성 다이어그램·캔버스 |

## 이 레포의 모듈 의존 그래프

```text
        config (OPEN)
           │
           ▼
        shared (OPEN)  ◀────────┐
           ▲                    │
           │                    │
         auth ──────────────────┘
           ▲
           │ (설계됨, 미구현)
       bootstrap ──▶ subscription

⛔ auth → subscription 은 영원히 금지 (순환의 씨앗)
```

→ [[auth 모듈]] · [[shared 모듈]] · [[config 모듈]]

## 자주 겪는 문제

| 증상 | 어디를 보나 | → 노트 |
|---|---|---|
| `verify()` — 순환 의존 | `allowedDependencies` | [[모듈 경계 검증]] |
| `verify()` — module not declared | `package-info.java` 누락 | [[Spring Modulith 아키텍처]] |
| 기동 실패 `missing table [event_publication]` | 수동 DDL 미적용 | [[모듈 간 통신 — 이벤트 우선]] |
| 기동 실패 `wrong column type` | `CHAR` 대신 `VARCHAR` | [[빌드와 검증 파이프라인]] |
| 401 인데 응답 본문이 비었다 | 진입점 등록 | [[shared 모듈]] |
| CORS 가 전부 막힌다 | `.cors()` 누락 | [[config 모듈]] |
| 이벤트가 가끔 유실된다 | `starter-jpa` 의존 · 아웃박스 테이블 | [[모듈 간 통신 — 이벤트 우선]] |

## 관련 노트

- [[Modulith 온보딩 맵]]
- [[Modulith 연습문제]]
