# 토스 연동 규칙 (구현 관점)

> 이 문서는 **Spring 코드에서 어떻게 구현하는지**만 다룬다. 비즈니스 흐름·API 계약은
> `docs/server/api-spec.md` 가 권위다. 플랫폼 스펙 자체는 apps-in-toss MCP 가 정본이다(`/b-toss-api`).

## 유저 식별 — 익명키 단일

**토스 로그인은 쓰지 않는다.** 전 구간 `X-Anonymous-Key` 하나로 식별한다.

| 방식 | 식별자 | 저장 |
|---|---|---|
| 익명키 (유일) | `User.getAnonymousKey()` 의 hash → `X-Anonymous-Key` 헤더값 | 우리 DB 의 사용자 식별자 |

- 익명키는 **미니앱마다 고유**하고 **같은 사용자는 항상 같은 값**을 받는다 —
  기기가 아니라 **토스 계정 기준**이므로 기기 변경·재설치에도 유지된다.
- **hash 인증으로 토스 서버 API 도 호출한다** (`x-anon-key` 헤더) — 대상은 **프로모션·스마트 발송·토스페이 3종**이다.
  ⚠️ **인앱결제(IAP)는 여기 없다** — IAP 서버 API 는 **mTLS + `orderId` 단독**이고 `x-anon-key` 를 쓰지 않는다
  (payment.md §9-1 전제 표에서 확정).
- ⚠️ **미니앱이 바뀌면 익명키도 바뀐다.** 앱 재출시 시 승계되지 않는다 — 이관 설계가 필요하다.
- 토스 로그인은 "앱인토스 밖 계정과 같은 사람인지 연결"할 때만 필요하다. 우리는 해당 없음.
  붙이려면 그것 자체가 기획 결정이다 — 임의로 추가하지 않는다.

## 익명키 흐름 (구현 관점)

```
1. 클라가 SDK 로 User.getAnonymousKey() 호출 → { type: 'HASH', hash }
2. 클라 → 우리 서버: 모든 요청에 X-Anonymous-Key: {hash}
3. 우리 서버: 형식만 검증하고 사용자 upsert (토스 verify API 는 호출하지 않는다 — 아래)
```

- ⚠️ **저장은 원문이 아니라 `SHA-256(익명키)` 다** (auth-design.md §3-2).
  원문을 저장하면 DB 제약 위반 메시지에 실려 로그로 새어 나간다 — 실제로 겪은 사고다.
- ⚠️ **샌드박스에서 `getAnonymousKey` 는 mock 을 반환한다** — 익명키 관련 검증은 실환경에서 한다.

### ❌ anon-key verify API 는 쓰지 않는다 (2026-08-07 결정)

- 토스 공식 답변: verify 는 *"hash 가 토스에서 발급된 유효한 값인지"* 만 확인하고
  **"요청자가 그 값의 소유자인지까지는 보장하지 않는다"**
  ([커뮤니티 4506](https://techchat-apps-in-toss.toss.im/t/getanonymouskey-api/4506)).
- 즉 위조는 걸러도 **실제 위협인 도용은 그대로 통과**한다. 위조범이 얻는 것은 빈 계정뿐이라
  막을 실익도 없다. 전체 근거는 [auth.md](../domain/auth.md) §4-3.
- 되살리려면 그 기각 근거부터 뒤집어야 한다. **mTLS 인증서는 인앱결제 때문에 여전히 필요하다** —
  verify 폐기와 무관하다.

## mTLS RestClient 구성 (인앱결제·스마트발송·프로모션에 필요)

- 토스 파트너 API(인앱결제 검증, 메신저 발송 `send-bulk-message`, 프로모션 등)는 **mTLS** 를 요구한다.
- **`RestClient` + JDK `HttpClient`(SSLContext 주입)** 를 쓴다. WebClient(reactor-netty)는
  `spring-boot-starter-webflux` 의존성이 새로 붙고 리액티브 스택이 섞이므로 쓰지 않는다
  (발송은 대개 fire-and-forget 배치 — 논블로킹 이득이 없다).
- 조립: PKCS12 클라 인증서 → `KeyManagerFactory` → `SSLContext` → `JdkClientHttpRequestFactory`
  → `RestClient.builder()` 를 **어댑터 생성자에서 직접** 조립한다.
- ⚠️ **Boot 4 는 `RestClient.Builder` 자동구성이 별도 모듈(`spring-boot-restclient`)** 이다.
  빌더 빈을 주입받는 설계는 기동 실패로 이어진다 — 정적 팩토리로 직접 만든다.
- **조립 게이트**: `enabled=false` 면 조립 생략(local/test·인증서 준비 전 운영 기동 허용).
  `enabled=true` 인데 인증서 설정이 비면 **기동 실패(fail-fast)** — 활성화했으면 인증서는 필수다.
- 인증서 경로·비밀번호는 **환경 변수로만** 주입한다. 커밋 금지, 로그·예외 메시지에도 출력 금지.
- 인증서 발급에는 리드타임이 있다. 기능 개발보다 먼저 신청한다.

## 인앱 결제(IAP) 검증

- 클라 결제 완료 → **서버가 토스 주문 상태 조회 API 로 검증한 뒤** 재화를 지급한다.
- `orderId` **멱등** 처리 필수 — 같은 주문으로 두 번 지급되지 않게 유니크 제약 + 멱등 키.
- 미결 주문 복원 흐름을 반드시 만든다(`getPendingOrders` → `completeProductGrant`).
  결제는 됐는데 지급이 안 된 상태를 사용자가 스스로 복구할 수 있어야 한다.

### 자동갱신 구독

- SDK: `createSubscriptionPurchaseOrder`(주문) · `getSubscriptionInfo`(상태) · 주기 WEEKLY/MONTHLY/YEARLY
- **구독 소유권의 정본은 우리 DB 의 `anonKey ↔ orderId` 매핑**이다. 로그인이 없으므로 이 매핑이
  끊기면 사용자는 돈을 내고 못 쓴다 — 유니크 제약·백업·정합성 요구가 일반 테이블보다 높다.
- 구현 형태: `anonKey → users(해시, auth 소유) → UserId → payment 테이블의 user_id FK` —
  payment 는 익명키를 저장하지 않고 **auth 가 노출한 타입 ID 만** 갖는다
  (→ [architecture.md](architecture.md) "타입화된 기본키", payment-design §2-1).
- 상태 변경은 `subscription.status_changed` **웹훅**으로 받는다(CREATED/RENEWED/EXPIRED/REVOKED 등).
  웹훅 URL 은 콘솔에 등록하고 `callback.registration_verification` 을 처리해야 등록이 완료된다.
- ⚠️ **구독 IAP 는 샌드박스 미지원** — 실결제로만 검증 가능하다. 테스트 계획을 미리 세운다.
- 수수료: 앱마켓 15% + 토스 5% = **20%**. 판매가는 VAT 포함가다.

## 기능성 알림

- 서버가 스케줄로 발송한다. 대상 조회 → 벌크 발송. **익명키(hash) 인증으로 발송한다** — 로그인 불필요.
- 배치 서비스는 모듈의 공개 API 가 아니다 — 모듈 안 `internal/` 에 두고 이벤트/스케줄로만 트리거한다.
- **동의 + 캠페인 검수** 두 게이트를 모두 통과해야 발송된다. 검수 전에는 테스트 발송도 안 된다.

## 서버 권위 원칙

재화·카운트·시간은 **서버만 신뢰**한다. 클라가 보낸 수치를 그대로 믿지 않는다.
충전·차감은 서버에서 검증하고 기록한다.

## MCP 활용

| 작업 | 도구 | 키워드(한국어 필수) |
|---|---|---|
| 검색 | `search_docs` | `사용자 식별키`, `mTLS`, `인앱 결제`, `정기 결제`, `알림 발송` |
| 상세 | `get_doc` | 검색 결과 id |

구현 전 반드시 MCP 로 최신 스펙을 확인하고 `docs/platform/` 레퍼런스와 대조한다.
