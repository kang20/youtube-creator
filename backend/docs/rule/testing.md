# 테스트 규칙 — 커버리지 100% 지향

> 단일 진실원천. CLAUDE.md·스킬·도메인 문서가 이 문서를 링크한다.

## 핵심 원칙

**테스트 없이 문서 없고, 문서 없이 프론트 작업 없다.**

- 컨트롤러 테스트 = REST Docs 스니펫 생성 (→ [rest-docs.md](rest-docs.md))
- 모듈 테스트 = `@ApplicationModuleTest` 로 해당 모듈만 부팅
- 모듈 경계 = `ApplicationModules.verify()` 자동 검증 (→ [architecture.md](architecture.md))

## 커버리지 — **목표 100%, 게이트 LINE 95% / BRANCH 90%**

- `./gradlew test` 후 `jacocoTestReport` 자동 실행(`finalizedBy`), 게이트는 `check` 에 연결.
- 리포트: `build/reports/jacoco/test/html/index.html`
- **미커버 라인이 남으면 "왜 테스트할 수 없는가"를 먼저 묻는다.** 대부분은 테스트가 어려운 게 아니라
  설계가 새는 신호다(정적 의존, 시간·랜덤 직접 사용, 과한 방어 코드).
- 게이트를 95%로 둔 이유: 100%를 강제하면 의미 없는 커버리지용 테스트가 생긴다. **95는 실패선이고
  목표는 100**이다. 새 기능을 머지할 때 그 기능의 변경 라인은 100%를 채운다.

### 제외 목록 (build.gradle.kts `coverageExclusions`)

| 대상 | 사유 |
|---|---|
| `*Application` | 부트스트랩 |
| `config/**`, `*Config` | 빈 정의뿐 — 통합 테스트가 간접 검증 |
| `dto/**`, `*Request`, `*Response` | record. 동작 없음 |

**여기에 새 항목을 추가하려면 이 표에 사유를 함께 적는다.** 사유 없는 제외는 커버리지 조작이다.

## 테스트 종류와 선택 기준

| 종류 | 어노테이션 | 언제 |
|---|---|---|
| 모듈 통합 | `@ApplicationModuleTest` | 서비스 로직 — 해당 모듈만 부팅해 빠르다 |
| 컨트롤러 슬라이스 | `@WebMvcTest` + `@AutoConfigureRestDocs` | HTTP 계약·문서화 |
| 전체 통합 | `@SpringBootTest` | 모듈 간 이벤트 흐름 검증 |
| 동시성 | `@SpringBootTest` + `ExecutorService`/`CountDownLatch` — **테스트 메서드에 `@Transactional` 금지** | UNIQUE 경쟁·조건부 UPDATE 불변식·잔량 음수 금지 검증 |
| 구조 | `ApplicationModules.verify()` | 항상 1개 |

### 동시성 테스트 — 비트랜잭션이어야 경쟁이 재현된다

- **테스트 메서드에 `@Transactional` 을 달면 경쟁이 사라진다.** 테스트가 연 트랜잭션과 영속성 컨텍스트를
  스레드들이 공유하거나 아예 못 보게 되어, 우리가 재현하려던 **커밋 대 커밋의 경쟁이 성립하지 않는다.**
- 게다가 테스트 트랜잭션은 끝에 **롤백**된다 — INSERT 가 커밋되지 않으니 `UNIQUE` 위반도, 조건부 UPDATE 의
  갱신 행 수 판정도 일어나지 않는다. 심판(DB 제약)이 부르지 않는 경기다(→ [architecture.md](architecture.md) "동시성").
- ⚠️ 롤백이 없으므로 **테스트가 남긴 데이터는 직접 지운다**(`@AfterEach` 에서 `deleteAll()` 등).
  안 지우면 다음 테스트가 남은 행을 보고 깨진다.

### @ApplicationModuleTest — 모듈 단위 통합

```java
@ApplicationModuleTest(mode = BOOTSTRAP_MODE.DIRECT_DEPENDENCIES)
class PostModuleTest {

    @Test
    void 글을_쓰면_PostCreated_이벤트가_발행된다(Scenario scenario) {
        scenario.stimulate(() -> postService.create(cmd))
                .andWaitForEventOfType(PostCreated.class)
                .toArriveAndVerify(ev -> assertThat(ev.postId()).isNotNull());
    }
}
```

- `Scenario` 는 비동기 이벤트를 기다렸다 검증한다 — `Thread.sleep` 금지.
- 다른 모듈의 리스너는 `@MockitoBean` 으로 대체하거나 `BOOTSTRAP_MODE` 로 범위를 좁힌다.

### 컨트롤러 테스트

- 서브클래스는 `@WebMvcTest(XxxController.class)` + 모듈별 `@MockitoBean` 만 추가한다.
- **성공 + 실패 케이스 모두 문서화** — 실패 응답도 프론트 계약이다.
- SSR(타임리프) 페이지 컨트롤러는 REST Docs 대상이 아니다 — status·viewName·model·권한을 검증한다.

## 테스트 프로파일 (application-test.yml)

| 항목 | 값 | 이유 |
|---|---|---|
| DataSource | `jdbc:p6spy:h2:mem:testdb;MODE=MYSQL` | MySQL 호환 인메모리 |
| Driver | `com.p6spy.engine.spy.P6SpyDriver` | SQL 로깅 |
| ddl-auto | `create-drop` | 테스트마다 스키마 생성 |
| open-in-view | `false` | 운영과 동일 조건 |
| `spring.modulith.events.jdbc.schema-initialization.enabled` | `true` | 이벤트 아웃박스 테이블 자동 생성 |

## 작성 원칙

1. **Fixture 클래스로 테스트 데이터 표준화** — 모듈별 `XxxFixture`.
2. **모듈 테스트는 실제 DB(H2)** — 리포지토리를 모킹하지 않는다.
3. **모듈 구현과 같은 커밋에 테스트를 넣는다** — 테스트 없는 머지 금지.
4. **시간·랜덤은 주입** — `Clock` 빈. 그래야 100%가 가능해진다.

## 실행

```bash
cd backend
./gradlew test                              # 전체 + 스니펫 + 커버리지
./gradlew test --tests "*ModularityTest"    # 모듈 경계만
./gradlew check                             # 커버리지 게이트 포함
```
