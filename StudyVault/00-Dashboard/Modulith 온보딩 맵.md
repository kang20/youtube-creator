---
module: dashboard
path: 00-Dashboard
keywords: MOC, onboarding, spring-modulith, module-boundary, ytcreator
---

# Spring Modulith 온보딩 맵 (ytcreator)

#dashboard #onboarding #arch-modulith

> **이 Vault 의 목적**: Spring Modulith 개념을 **이 레포의 실제 코드**로 익힌다.
> 교과서 설명이 아니라 `backend/src` 에 실제로 있는 것만 다룬다 — 모든 예시는 실행되는 코드다.

## 아키텍처 한눈에

- **패턴**: Spring Modulith (모듈러 모놀리스). **레이어드도 헥사고날도 아니다** → [[Spring Modulith 아키텍처]]
- **기술 스택**: Spring Boot 4.0.6 · Spring Modulith 2.0.7 · Java 25 · Gradle(Kotlin DSL) · JPA · H2(test)/MySQL(운영)
- **핵심 명제**: **패키지 = 애플리케이션 모듈**, 경계는 사람이 아니라 **테스트가 강제**한다

```text
kang20.ytcreator/
├── YtcreatorApplication      모듈 스캔의 루트
├── shared/     (OPEN)        예외·시간·보안 — 누구나 참조 가능
├── config/     (OPEN)        전역 스프링 설정
└── auth/                     도메인 모듈 — allowedDependencies = { shared }
    ├── AuthService           ← 모듈 밖에서 보이는 유일한 타입
    ├── dto/Registration      ← 반환 타입이라 함께 공개
    └── internal/             ← 밖에서 참조하면 테스트가 깨진다
```

## 모듈 맵

| 모듈 | 성격 | 책임 | 허용 의존 | 노트 |
|---|---|---|---|---|
| `shared` | **OPEN** | 예외·에러코드·감사시각·익명키 게이트 | — | [[shared 모듈]] |
| `config` | **OPEN** | 보안·CORS·Clock·Auditing 빈 정의 | `shared` | [[config 모듈]] |
| `auth` | 도메인 | 익명키로 사용자 등록·식별 | `shared` | [[auth 모듈]] |
| `bootstrap` | 집계 | *(미구현)* 진입 응답 조립 | `shared`,`auth`,`subscription` | [[auth 모듈]] §설계된 미래 |

## API 표면

| 메서드 | 경로 | 인증 | 모듈 |
|---|---|---|---|
| — | **HTTP 엔드포인트가 아직 없다** | — | — |
| GET | `/actuator/modulith` | 공개 | 런타임 모듈 구조 확인 → [[모듈 경계 검증]] |
| GET | `/actuator/health`, `/actuator/prometheus` | 공개 | 운영 |

> [!important] auth 에 컨트롤러가 없는 것은 실수가 아니라 **설계 결정**이다
> 진입 응답(`POST /api/v1/bootstrap`)은 집계 모듈이 소유한다. auth 가 그걸 가지면
> 결제를 알게 되어 모듈 경계가 무너진다 — 근거는 [[auth 모듈]] §설계된 미래.
> 이 규칙은 `AuthModuleBoundaryTest` 가 **테스트로 강제**한다.

## 시작하기

```bash
cd backend
./gradlew test --tests "*ModularityTest"   # 모듈 경계만 빠르게 검증
```

```bash
cd backend
./gradlew check                            # 전체 테스트 + 경계 + 커버리지 게이트
```

자세한 명령은 → [[Quick Reference]]

## 태그 색인

| 태그 | 의미 | 규칙 |
|---|---|---|
| `#arch-*` | 아키텍처 개념 | `arch-modulith`, `arch-module-boundary`, `arch-event-driven` |
| `#module-*` | 모듈별 | 모듈 하나당 하나. `module-auth`, `module-shared`, `module-config` |
| `#pattern-*` | 구현 패턴 | `pattern-open-module`, `pattern-internal-package`, `pattern-outbox`, `pattern-named-interface` |
| `#test-*` | 테스트 | `test-modularity`, `test-module-integration` |
| `#config-*` | 빌드·설정 | `config-gradle` |
| `#api-*` | API 표면 | 현재 없음 (엔드포인트 부재) |

**규칙**: 태그는 **영어 kebab-case** 만 쓴다. 세부 태그를 달면 상위 도메인 태그를 **함께** 단다
(예: `#pattern-internal-package` 를 쓰면 `#arch-module-boundary` 도 같이).

## 온보딩 경로

> Modulith 가 처음이면 이 순서로 읽는다. 1~3 이 개념, 4~6 이 이 레포의 적용, 7 이 실습이다.

1. [[Spring Modulith 아키텍처]] — 왜 Modulith 인가, 패키지=모듈이 무슨 뜻인가
2. [[모듈 경계 검증]] — `verify()` 가 잡는 것과 **못 잡는 것**
3. [[모듈 간 통신 — 이벤트 우선]] — 순환 의존을 이벤트로 뒤집기, 아웃박스
4. [[shared 모듈]] — OPEN 모듈이 왜 필요한가
5. [[config 모듈]] — 설정이 도메인을 참조하면 안 되는 이유
6. [[auth 모듈]] — 실제 도메인 모듈 하나를 끝까지
7. [[Modulith 연습문제]] — 코드 추적·설정·디버깅·확장 20문항

> [!tip] 이미 퀴즈 기록이 있다
> `/tutor` 로 푼 [[스프링-트랜잭션]] 개념 추적이 [[학습-대시보드]] 에 있다.
> auth 모듈의 `register` 설계를 이해하려면 그쪽을 먼저 보는 게 빠르다.

## 관련 노트

- [[Quick Reference]]
- [[빌드와 검증 파이프라인]]
- [[Modulith 연습문제]]
