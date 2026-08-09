---
module: architecture
path: 01-Architecture
keywords: spring-modulith, modular-monolith, application-module, package-boundary
---

# Spring Modulith 아키텍처

#arch-modulith #arch-module-boundary

## 한 줄

**패키지 하나 = 애플리케이션 모듈 하나.** 모듈 루트의 public 타입만 밖에서 보이고, 그 경계를 **테스트가 강제**한다.

## 왜 Modulith 인가

레이어(`controller` / `service` / `repository`)로 자르면 두 가지가 망가진다.

| 문제 | 레이어드 | Modulith |
|---|---|---|
| 기능 하나가 어디 있나 | **세 군데로 흩어진다** | 패키지 하나에 모인다 |
| 경계를 누가 지키나 | **사람이** (코드 리뷰) | **테스트가** (`verify()`) |
| 모듈 간 결합 | import 하면 그만 | `allowedDependencies` 에 적어야 통과 |
| 마이크로서비스로 쪼갤 때 | 경계가 없어 어디를 자를지 모른다 | 모듈 경계가 곧 절취선 |

> [!important] 핵심 규칙은 하나뿐이다
> **모듈 루트 패키지의 public 타입만 외부에 보인다.**
> `internal/` 이하는 `public` 이어도 다른 모듈이 참조하면 검증 테스트가 깨진다.
> 자바 접근제어자로는 못 하는 일을 Modulith 가 **테스트로** 해낸다.

## 패키지 레이아웃 (이 레포)

```text
kang20.ytcreator/
├── YtcreatorApplication.java      @SpringBootApplication — 모듈 스캔의 루트
├── shared/                        OPEN 모듈 (공유 커널)
│   └── package-info.java          @ApplicationModule(type = Type.OPEN)
├── config/                        OPEN 모듈 (전역 설정)
└── auth/                          ← 도메인 모듈 하나
    ├── package-info.java          @ApplicationModule(displayName, allowedDependencies)
    ├── AuthService.java           모듈 공개 API — 밖에서 부를 수 있는 유일한 축
    ├── dto/Registration.java      반환 타입이라 함께 공개
    └── internal/                  엔티티·리포지토리·구현체 — 밖에서 접근 금지
        ├── User.java
        ├── UserRepository.java
        ├── UserWriter.java
        └── AnonymousKeyHasher.java
```

## `@ApplicationModule` 읽는 법

실제 코드 — `backend/src/main/java/kang20/ytcreator/auth/package-info.java`:

```java
@ApplicationModule(displayName = "인증", allowedDependencies = {"shared"})
package kang20.ytcreator.auth;
```

| 속성 | 의미 | 안 적으면 |
|---|---|---|
| `displayName` | 다이어그램·문서에 표시될 이름 | 패키지명이 쓰인다 |
| `allowedDependencies` | **부를 수 있는 모듈의 화이트리스트** | **아무 모듈도 못 부른다** (완전 격리) |
| `type` | `CLOSED`(기본) / `OPEN` | `CLOSED` — 하위 패키지가 감춰진다 |

> [!warning] `allowedDependencies` 를 빈 배열로 두면 완전 격리다
> 생략과 빈 배열은 **같다**. 새 의존을 추가할 때 여기 적으면서
> "이 결합이 정말 필요한가"를 한 번 되묻게 만드는 게 이 구조의 요점이다.

## CLOSED vs OPEN

| | CLOSED (기본) | OPEN |
|---|---|---|
| 하위 패키지 | **감춰진다** (`internal/` 등) | 전부 노출된다 |
| 용도 | 도메인 모듈 | 공유 커널·전역 설정 |
| 이 레포 | `auth` | `shared`, `config` |

```java
// shared/package-info.java — 실제 코드
@ApplicationModule(displayName = "공유 커널", type = ApplicationModule.Type.OPEN)
package kang20.ytcreator.shared;
```

> [!tip] OPEN 을 남발하면 Modulith 를 쓰는 의미가 없어진다
> `shared` 가 OPEN 인 대신 **"도메인 지식이 없는 것만" 둔다**는 규칙이 붙는다.
> 특정 도메인 냄새가 나면 그 모듈로 옮긴다 — 안 그러면 `shared` 가 쓰레기통이 된다.

## 이 레포가 Modulith 를 쓰는 방식 — 실제 결정 3가지

### ① 게이트를 도메인 모듈이 아니라 `shared` 에 뒀다

익명키 인증 게이트(필터·진입점·형식검증)는 `auth` 가 아니라 `shared/security` 에 있다.

```text
❌ config → auth        게이트를 auth 에 두면 SecurityConfig 가 도메인을 참조한다
✅ config → shared      게이트는 전 도메인 공통 장치다
```

**이유**: 게이트는 모든 요청 앞단에 있는 공통 장치이지 `auth` 의 소유물이 아니다.
`auth` 에 두면 `config → auth` 의존이 생기고, `auth` 가 가장 밑에 있어야 한다는 전제가 깨진다.

### ② `auth` 의 허용 의존이 `shared` 하나뿐이다

```java
allowedDependencies = {"shared"}
```

가장 밑에 있어야 할 모듈이 실제로 가장 밑에 놓였다.
⚠️ **`auth` 는 `subscription` 을 영원히 참조하지 않는다** — 참조하면 순환의 씨앗이 된다.

### ③ 진입 응답을 제3의 집계 모듈이 조립한다

```text
POST /api/v1/bootstrap
   └─▶ bootstrap (allowedDependencies = { shared, auth, subscription })
          ├─▶ AuthService          "너 누구야"
          └─▶ SubscriptionService  "구독 중이야?"
```

`auth` 가 직접 조립하면 `auth → subscription` 이 생기고, `subscription` 이 사용자를 참조하면
**순환이 닫힌다.** 집계 모듈은 **한 방향으로만** 양쪽을 참조하므로 순환이 안 생긴다.

> [!warning] 쓰지 않는 말
> 이 프로젝트는 **레이어드도 헥사고날도 아니다.** 아래 용어를 쓰면 규칙과 충돌하고 처방도 틀리게 나온다.
>
> | 쓰지 않는 말 | 대신 |
> |---|---|
> | 인바운드/아웃바운드 포트, 어댑터 | 모듈 공개 API(`XxxService`), `internal/` |
> | 레이어(presentation/application/infra) | 애플리케이션 모듈(패키지) |
> | 순환 참조 → 포트 분리 | 순환 의존 → **이벤트로 뒤집기** |

## 새 모듈을 만들 때 생기는 파일

```text
{name}/package-info.java              @ApplicationModule
{name}/{Name}Service.java             모듈 공개 API
{name}/dto/                           요청·응답 record
{name}/internal/{Name}.java           엔티티
{name}/internal/{Name}Repository.java
src/test/.../{name}/{Name}ModuleTest.java
```

## 흔한 실수

| 증상 | 원인 | 해결 |
|---|---|---|
| `verify()` 가 "module not declared" | 최상위 패키지에 `package-info.java` 없음 | 모듈로 만들거나 `shared` 로 옮긴다 |
| 두 모듈이 서로 참조 | 순환 의존 | 한쪽을 **이벤트 구독으로 뒤집는다** → [[모듈 간 통신 — 이벤트 우선]] |
| 엔티티를 다른 모듈에서 쓰고 싶다 | **경계 설계 실패 신호** | 필요한 값만 담은 record 를 모듈 루트에 노출하거나 이벤트로 전달 |
| 공용 유틸을 모든 모듈이 참조 | `shared` 가 OPEN 이 아님 | `type = Type.OPEN` |

## 관련 노트

- [[모듈 경계 검증]] — `verify()` 가 잡는 것과 못 잡는 것
- [[모듈 간 통신 — 이벤트 우선]]
- [[auth 모듈]] · [[shared 모듈]] · [[config 모듈]]
- [[Modulith 온보딩 맵]]
