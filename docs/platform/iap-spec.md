# 앱인토스 인앱결제(IAP) 플랫폼 스펙 — 레퍼런스

> **조사일 2026-08-09.** 1차 출처는 [앱인토스 개발자센터](https://developers-apps-in-toss.toss.im) 이고,
> 2차 출처는 [개발자 커뮤니티](https://techchat-apps-in-toss.toss.im) **중 토스 직원 답변만** 인정했다.
> 모든 인용을 1차 출처 재조회로 교차검증했다.
>
> **이 문서는 사실만 적는다.** 우리 서비스의 결정·정책은 여기 없다 →
> [backend/docs/domain/payment.md](../../backend/docs/domain/payment.md)
>
> 우리 판매 상품: **단건 2,200원(1회 이용권)** · **월 자동갱신 구독 3,960원**

---

## 0. ⚠️ 먼저 — 이름이 비슷한 다른 제품 3개

| 제품 | 무엇 | 우리가 쓰는가 |
|---|---|---|
| **앱인토스 IAP (인앱결제)** | 미니앱 안의 인앱결제. **구글플레이/앱스토어 결제 위에 얹힌다.** 수수료 앱마켓 15% + 토스 5% | ✅ **이것** |
| **토스페이 (TossPay)** | 앱인토스 안의 **다른** 결제 수단. 실물·서비스 결제용. `pay-apps-in-toss-api.toss.im` 도메인 | ❌ |
| **토스페이먼츠 (TossPayments)** | `docs.tosspayments.com` — **별개 회사 제품(PG).** 앱인토스와 무관 | ❌ |

- 검수 체크리스트도 **“인앱 결제”와 “토스페이”를 완전히 분리된 별개 섹션**으로 둔다.
  토스페이 섹션의 “다른 결제 수단 병행 제공 금지” 항목은 IAP 를 쓰는 우리에게 적용되지 않는다.
- `paymentKey` · `billingKey` · `결제위젯` · `시크릿키` 는 전부 **토스페이먼츠 용어**다.
  IAP 의 식별자는 `orderId` 와 `sku` 뿐이다.

---

## 1. 상품 유형 — 우리 상품이 무엇으로 등록되는가

공식 문서가 정의하는 유형은 3종이다:

> **소모품** — 사용하면 소진되는 상품이에요. 다시 사용하려면 재구매해야 해요.
> 예를 들어 게임 아이템, 내부 재화 충전, **1회 이용권**이 있어요.
> **비소모품** — 한 번 구매하면 계속 사용할 수 있는 상품이에요. 예를 들어 광고 제거, 소장형 콘텐츠가 있어요.
> **자동 갱신 구독** — 정해진 주기마다 자동으로 결제되며, 취소 전까지 계속 이용할 수 있어요.
> ([인앱 결제 가이드](https://developers-apps-in-toss.toss.im/guide/monetization/in-app-payment))

| 우리 상품 | 등록 유형 | 근거 |
|---|---|---|
| 단건 2,200원 (1회 이용) | **소모품 (CONSUMABLE)** | 문서가 **“1회 이용권”을 소모품 예시로 직접 든다** |
| 월 3,960원 (무제한) | **자동 갱신 구독 (SUBSCRIPTION)**, `renewalCycle: 'MONTHLY'` | — |

**유형에 따라 주문 생성 함수가 갈린다** — 이것이 클라이언트 분기의 근원이다:

| 유형 | 주문 생성 함수 |
|---|---|
| `CONSUMABLE` · `NON_CONSUMABLE` | `IAP.createOneTimePurchaseOrder` |
| `SUBSCRIPTION` | `IAP.createSubscriptionPurchaseOrder` |

---

## 2. 가격 · 수수료 · 정산

### 2-1. 가격 등록 규칙 — 우리 가격은 성립한다

| 규칙 | 값 |
|---|---|
| 입력 단위 | **공급가(VAT 제외)만 입력.** 판매가는 `공급가 + VAT` 로 자동 계산 |
| 범위 | 공급가 **최소 400원 ~ 최대 1,400,000원** |
| 단위 | **10원 단위**만 입력 가능 (티어 방식이라는 서술은 문서에 없다) |
| 등록 개수 | 비게임 미니앱 **최대 30개** (게임 80개) |

| 우리 상품 | 판매가 | 공급가(입력값) | 규격 충족 |
|---|---|---|---|
| 단건 | **2,200원** | 2,000원 | ✅ 10원 단위 · 400원 이상 |
| 월 구독 | **3,960원** | 3,600원 | ✅ |

- ⚠️ **판매가 역산 입력은 지원하지 않는다.** 2,200 / 3,960 을 얻으려면 공급가 2,000 / 3,600 을 직접 입력한다.
- 화면 가격은 **SDK `getProductItemList` 의 `displayAmount`** 를 써야 한다. 체크리스트가
  “주문 금액과 구글/애플 결제창 금액 일치”를 요구하므로 **앱에서 가격을 하드코딩하면 불일치 위험**이 있다.

### 2-2. 수수료 — 계산 기준이 둘로 나뉜다

| 항목 | 요율 | **기준** |
|---|---|---|
| 앱마켓 수수료 | 15% (매출 증가 시 30% 로 오를 수 있음) | **공급가(VAT 제외)** |
| 토스 수수료 | 5% | **결제 금액(VAT 포함)** |
| 토스 수수료 부가세 | 토스 수수료의 10% | — |

- **iOS 는 앱마켓 수수료에 VAT 가 얹힌다.** 공식 예시(결제 11,000원):
  iOS 정산금 **8,745원**(앱마켓 1,650 + 토스 550 + 토스VAT 55 차감) /
  Android **8,895원**(앱마켓 1,500 + 토스 550 + 토스VAT 55 차감).
- CBT 기간에는 토스 수수료가 0% 다.

**우리 상품 실수령액 (15% 기준)**

| 상품 | 판매가 | iOS | Android |
|---|---|---|---|
| 단건 | 2,200원 | **1,749원** (79.5%) | **1,779원** (80.9%) |
| 월 구독 | 3,960원 | **약 3,148원** (79.5%) | **약 3,202원** (80.9%) |

- ⚠️ 구독은 토스 수수료 부가세가 **19.8원**(정수가 아님)이고 **절사·반올림 규칙이 문서에 없어**
  3,148원인지 3,149원인지 확정할 수 없다.
- ⚠️ **앱마켓 수수료가 30% 로 오르면** 단건 iOS 약 1,419원 / Android 1,479원,
  구독 iOS 약 2,554원 / Android 2,662원으로 떨어진다(수취율 iOS −15.0%p · Android −13.6%p).
  **오르는 매출 임계값은 문서에 없다**(“총 수익이 늘어나면”이라고만 안내).

### 2-3. 정산

- 지급일은 **고정일이 아니다.** 앱마켓이 토스에 대금을 입금한 날 기준 **3영업일 내**.
  공식 예시: 5월 매출분을 앱마켓이 6/5 에 지급 → 파트너사는 6/8 까지 수령.
  즉 **매출 발생부터 실입금까지 한 달 이상** 걸린다.
- 정산 정보 등록은 워크스페이스 ‘정보’ 탭 → 검토 요청, **영업일 평균 2~3일**.
  예금주명이 통장 사본과 한 글자라도 다르면 지연된다.
- 성과 대시보드는 **D+1 오전 8시 이후** 순차 업데이트 — 실시간 매출 확인 불가.
- 환불이 발생하면 해당 건의 **현금영수증도 취소**되고 월별 집계에서 차감된다.

---

## 3. 클라이언트 SDK (`@apps-in-toss/web-framework` · `IAP.*`)

### 3-1. 함수별 최소 지원 버전 — **함수마다 다르다**

| 함수 | Android | iOS |
|---|---|---|
| `getProductItemList` · `createOneTimePurchaseOrder` | 5.219.0 | 5.219.0 |
| `getPendingOrders` | 5.234.0 | 5.231.0 |
| `getCompletedOrRefundedOrders` | 5.231.0 | 5.231.0 |
| `completeProductGrant` | 5.233.0 | 5.233.0 |
| **`createSubscriptionPurchaseOrder`** | **5.248.0** | **5.249.0** |
| `getProductItemList` 의 구독 상품 포함 | 5.248.0 | 5.250.0 |
| **`getSubscriptionInfo`** | **5.253.0** | **5.250.0** |

> **실질 하한 = Android 5.253.0 / iOS 5.250.0** (가장 높은 값)
> ⚠️ **구독을 목록에 포함시키는 최소 버전(5.248.0/5.250.0)이 단건(5.219.0)보다 높다.**
> 즉 낮은 버전에서는 **같은 목록 API 가 단건만 반환한다.**
> `npm` SDK 도 **`@apps-in-toss/web-framework` 2.6.2 이상**이 필요하다 — 그 미만에는
> **구독 결제에서 `processProductGrant` 가 호출되지 않는 버그**가 있었고 2.6.2 에서 수정됐다.

- 모든 함수에 `IAP.<함수명>.isSupported()` 가 있고, 문서가 `UNSUPPORTED_APP_VERSION` 대응책으로
  사전 확인을 지시한다. ⚠️ 다만 **각 함수 문서의 에러 표 안에서만 언급**되고
  **시그니처·반환 타입은 문서화돼 있지 않다**(동기/비동기 불명).

### 3-2. 상품 목록 — `getProductItemList()`

```ts
type IapProductListItem =
  | ConsumableProductListItem      // sku, type:'CONSUMABLE', displayName, displayAmount, iconUrl, description
  | NonConsumableProductListItem
  | SubscriptionProductListItem;   // + renewalCycle:'WEEKLY'|'MONTHLY'|'YEARLY', offers?: Offer[]
```

- **단건과 구독이 한 목록으로 함께** 내려온다. `type` 으로 분기한다.
- `sku` 는 **`createOneTimePurchaseOrder` 를 호출할 때 쓰는 `productId` 와 동일한 값**이며,
  목록 응답의 `sku` 필드로 내려온다. → **목록에서 받은 값을 그대로 주문 생성에 넘긴다.**
- ⚠️ `offers` 는 **optional** 이다. 프로모션이 없으면 아예 안 내려오므로 무조건 순회하면 런타임 에러가 난다.
- ⚠️ **`IapProductListItem` 정의가 문서 3곳에서 서로 다르다** — SDK 레퍼런스는 `type` 포함 3-way 유니온,
  공통 인앱결제 문서는 **`type` 필드가 아예 없는 5필드 평면 인터페이스**, 정기결제 문서는 유니온 + `hint?`.
  **낮은 앱 버전에서 `type` 이 안 내려올 수 있다고 보고 방어해야 한다.**

### 3-3. 주문 생성

```ts
// 단건 (소모품)
IAP.createOneTimePurchaseOrder({
  options: {
    sku: string,                    // 필수. (productId 는 deprecated)
    processProductGrant: (p: { orderId: string }) => boolean | Promise<boolean>,
  },
  onEvent: (e: { type: 'success', data: IapCreateOneTimePurchaseOrderResult }) => void,
  onError: (error: unknown) => void,
}): () => void                      // cleanup — 반드시 호출

// 구독
IAP.createSubscriptionPurchaseOrder({
  options: {
    sku: string,                    // 필수
    offerId?: string | null,        // 선택. 생략/null 이면 기본 가격
    processProductGrant: (p: { orderId: string, subscriptionId?: string }) => boolean | Promise<boolean>,
  },
  onEvent, onError,
}): () => void
```

- `onEvent` 결과 타입은 **단건과 구독이 동일**하다
  (`type IapCreateSubscriptionPurchaseOrderResult = IapCreateOneTimePurchaseOrderResult`):
  `orderId` · `displayName` · `displayAmount` · `amount` · `currency` · `fraction` · `miniAppIconUrl`.
  **백엔드에 넘길 값은 `orderId`** 다.
- ⚠️ 구독의 `subscriptionId` 는 **optional** 이다. 서버 설계에서 필수로 가정하면 안 된다.
- 필수/선택은 **TypeScript 타입의 `?` 유무가 유일한 근거**다(별도 표가 없다).

### 3-4. ⏱️ 30초 제한 — 백엔드 응답 예산의 근원

> **결제 성공 후 30초내에 `processProductGrant` 콜백이 호출되지 않거나 해당 콜백의 결과가
> true가 아닌 경우, `{appName}에 문제가 생겼어요. 환불을 신청해주세요` 페이지가 노출될 수 있어요.**
> ([일회성 결제 가이드](https://developers-apps-in-toss.toss.im/documentation/common/monetization/iap/in-app-purchase.md))

- ⚠️ **이 문구는 일회성 결제 문서에만 있다.** 구독 문서에는 없다. 다만 커뮤니티에
  구독 결제에서도 약 30초 뒤 같은 환불 안내가 뜬 사례가 보고돼 있어
  ([4005](https://techchat-apps-in-toss.toss.im/t/topic/4005)) **구독에도 적용된다고 보는 편이 안전하다.**
- ⚠️ **타이머 기점이 문서에 없다** — 결제 승인 시점인지 콜백 진입 시점인지 불명.
- ⚠️ [4329](https://techchat-apps-in-toss.toss.im/t/ios-webview-processproductgrant-true-ack-30/4329):
  `processProductGrant` 가 **true 를 반환했는데도** ack 미반영으로 30초 뒤 환불 안내가 뜨고,
  주문이 `PAYMENT_COMPLETED` 로 남다가 미니앱 복귀 시에만 `PURCHASED` 로 전환된 사례.

### 3-5. 지급 완료 처리 — `completeProductGrant`

```ts
IAP.completeProductGrant({ params: { orderId: string } }): Promise<boolean | undefined>
```

- **IAP 함수 중 유일하게 `{ params: { ... } }` 중첩 구조**를 쓴다.
- 호출하지 않으면 주문이 **`PAYMENT_COMPLETED`(결제 완료·상품 지급 실패)** 로 남고
  콘솔 결제 내역에도 ‘결제 완료’로 표시되며 ‘지급완료’가 되지 않는다.
- ⚠️ **`processProductGrant`(주문 생성 옵션의 콜백)와 `completeProductGrant`(별도 SDK 함수)는 다른 것이다.**
- ⚠️ **두 함수의 선후 관계가 문서에 없다.** 공식 예제는 `processProductGrant` 만 쓰고
  `completeProductGrant` 를 호출하지 않는다.

### 3-6. 복원 함수 — **둘의 용도가 다르다**

| 함수 | 반환 | 무엇을 잡나 |
|---|---|---|
| `getPendingOrders()` | `Promise<{ orders: Order[] } \| undefined>`<br>`Order = { orderId, sku, paymentCompletedDate? }` | **결제 완료 + 상품 미지급** 주문. 30초 초과·앱 강제종료·네트워크 단절로 지급이 누락된 건 |
| `getCompletedOrRefundedOrders()` | `Promise<{ hasNext, nextKey?, orders: { orderId, sku, status:'COMPLETED'\|'REFUNDED', date }[] }>` | **지급까지 끝난 주문 + 환불된 주문**. 한 페이지 최대 50건 |

- ⚠️ **`getCompletedOrRefundedOrders` 에는 미지급 주문이 포함되지 않는다.**
  지급 누락 복원은 **반드시 `getPendingOrders`** 로 해야 한다.
- ⚠️ 반대로 **기기 변경 후 과거 완료 구매 복원은 `getPendingOrders` 로 안 된다** —
  이미 지급된 건은 pending 이 아니다. `getCompletedOrRefundedOrders` 를 써야 한다.
- ⚠️ **웹 SDK 에서 `getCompletedOrRefundedOrders` 는 파라미터를 받지 않고 항상 첫 페이지만** 가져온다
  (SDK 레퍼런스 명시). 우리 프론트는 `@apps-in-toss/web-framework` 이므로
  **커서 페이지네이션을 계약에 넣으면 안 된다.** 50건 초과는 우리 서버 기록으로 보완해야 한다.
- ⚠️ `getPendingOrders` 의 `sku` 는 SDK 1.4.2 + (Android 5.234.0 / iOS 5.231.0) 부터,
  `paymentCompletedDate` 는 SDK 1.4.8 부터 내려온다. **낮은 버전에서는 `orderId` 만 온다** —
  서버가 `orderId` 만으로 상품을 역추적할 수 있어야 한다.

### 3-7. 에러 코드 (`createOneTimePurchaseOrder`, SDK 레퍼런스가 정본)

`INVALID_PRODUCT_ID` · `PAYMENT_PENDING` · `NETWORK_ERROR` · `INVALID_USER_ENVIRONMENT` ·
`ITEM_ALREADY_OWNED` · `APP_MARKET_VERIFICATION_FAILED` · `TOSS_SERVER_VERIFICATION_FAILED` ·
`INTERNAL_ERROR` · `KOREAN_ACCOUNT_ONLY`(iOS 한국 계정 아님) · `USER_CANCELED` ·
`PRODUCT_NOT_GRANTED_BY_PARTNER` · `UNSUPPORTED_APP_VERSION`

- ⚠️ **공통 인앱결제 문서에는 `INVALID_PRODUCT_ID` 하나만 실려 있다.** 공통 문서만 보고 구현하면 11종을 놓친다.
- 구독 함수의 문서화된 에러는 `UNSUPPORTED_APP_VERSION` 하나뿐이다.
- ⚠️ `ITEM_ALREADY_OWNED` 가 **소모품 재구매에도 발생하는지는 근거가 없다.** 정기결제 문서는
  `CONSUMABLE` 을 *“구매 후 여러 번 재구매할 수 있어요”*, `NON_CONSUMABLE` 을
  *“동일 계정에서는 재구매하지 않아요”* 로 구분하므로 **비소모품 쪽 에러로 읽는 편이 자연스럽다.**
  샌드박스 필수 시나리오 ②에서 실측할 것.

### 3-8. 구독 상태 조회 — `getSubscriptionInfo` (클라 전용)

```ts
IAP.getSubscriptionInfo({ params: { orderId } })
  : Promise<{ subscription: IapSubscriptionInfoResult } | undefined>

type IapSubscriptionInfoResult = {
  catalogId: number;
  status: 'ACTIVE'|'EXPIRED'|'IN_GRACE_PERIOD'|'ON_HOLD'|'PAUSED'|'REVOKED';
  expiresAt: string | null;             // 구독 만료 예정 시각
  gracePeriodExpiresAt: string | null;  // 결제 유예 기간 만료 시각 (expiresAt 과 다른 시점)
  isAutoRenew: boolean;
  isAccessible: boolean;
}
```

- ⚠️ **`isAccessible` 의 산식이 문서에 정의돼 있지 않다.** “현재 구독 상품을 이용할 수 있는지 여부”라고만 적혀 있다.
  특히 `IN_GRACE_PERIOD` · `ON_HOLD` · `PAUSED` 에서 true 인지 false 인지 알 수 없다.
  토스 직원(Dylan)은 `expiresAt` 이 null 일 때의 권한 판단을 묻자 **`status` 기준으로 하라**고 답했다.
- ⚠️ `status` 6종의 의미가 **한 단어 대응표뿐**이다(ACTIVE=활성, EXPIRED=만료 …).
  전이 조건·유예 기간 길이·`REVOKED` 와 `EXPIRED` 의 차이는 문서에 없다.
- ⚠️ 최소 버전 미만이면 **`undefined`** 를 반환한다 — 반드시 방어해야 한다.
- ⚠️ 구독 구매 **직후 `expiresAt` 이 null 로 내려온 사례**가 커뮤니티에 보고됐고(주간·월간·연간 모두),
  토스는 “가이드 업데이트”로만 답했다. **정상 동작인지 버그인지 미확정.**

---

## 4. 서버 파트너 API

### 4-1. 존재하는 IAP 서버 API 는 **단 하나다**

| 항목 | 값 |
|---|---|
| 엔드포인트 | `POST /api-partner/v1/apps-in-toss/order/get-order-status` |
| Base URL | `https://apps-in-toss-api.toss.im` |
| 요청 | `{ "orderId": "..." }` (필수 · 단일 필드) |
| 인증 | **mTLS 단독.** OpenAPI spec 의 `parameters: []`, 보안 스킴 `mutualTLS` |
| 한도 | 미니앱당 **분당 3,000 QPM** (초과 시 `errorCode: 4095` + `error.data.retryAfterSeconds`) |

> ⚠️ **구독 상태를 서버가 조회하는 API 는 존재하지 않는다.**
> IAP 태그의 서버 API 는 `get-order-status` 1개뿐이고, 구독 상태 확인 경로는
> **클라 SDK `getSubscriptionInfo` 와 웹훅 두 가지뿐**이다.
> (사이트맵 · 서버 API 문서 · 구독 개발 가이드 3곳 대조, **2026-08-09 관측 기준**)

**응답**

```json
{ "resultType": "SUCCESS",
  "success": { "orderId": "...", "status": "PURCHASED", "reason": "완료된 주문이에요.",
               "sku": "...", "statusDeterminedAt": "..." } }
```

- `sku` · `statusDeterminedAt` 은 **선택** — `MINIAPP_MISMATCH` · `NOT_FOUND` · `ERROR` 일 때는 안 내려온다.
- 실패: `{ "resultType": "FAIL", "success": null, "error": { errorType, errorCode, reason, data, title } }`
- ⚠️ **`resultType` 은 `SUCCESS`/`FAIL` 외에도** `HTTP_TIMEOUT` · `NETWORK_ERROR` · `EXECUTION_FAIL` ·
  `INTERRUPTED` · `INTERNAL_ERROR` 를 가질 수 있다 → **“SUCCESS 가 아니면 전부 실패”로 처리해야 한다.**
- ⚠️ **비즈니스 오류는 HTTP 200 으로 내려온다.** 요청 필드 검증 실패만 400, 미분류 서버 오류가 500 이다.
  HTTP 상태만 보고 성공 판정하면 안 된다.
- ⚠️ **전역 errorCode 목록은 공개돼 있지 않다.** `get-order-status` 는 `4095`(요청 한도 초과) 하나,
  익명키 검증 API 는 `4010`·`4095` 만 문서화돼 있다.

**`status` 8종**

| 값 | 의미 |
|---|---|
| `ORDER_IN_PROGRESS` | 주문이 아직 진행 중 |
| `PAYMENT_COMPLETED` | 결제 완료 (`reason`: “결제가 완료되었어요.”) — **상품 지급은 안 끝난 상태** |
| `PURCHASED` | 구매 완료 (`reason`: “완료된 주문이에요.”) |
| `FAILED` | 구매 실패 |
| `REFUNDED` | 환불됨 |
| `NOT_FOUND` | 주문을 찾을 수 없음 |
| `MINIAPP_MISMATCH` | 요청한 미니앱의 주문이 아님 |
| `ERROR` | 카탈로그 조회 실패 등으로 정상 범위를 벗어난 상태 |

- ⚠️ **어느 상태에서 지급해야 하는지에 대한 규정이 문서에 없다.** `PAYMENT_COMPLETED` 와 `PURCHASED`
  둘 다 지급 대상으로 볼지, `PURCHASED` 만 볼지는 **추론일 뿐**이다. 소모품에서 틀리면
  **이중 지급 또는 지급 누락**으로 직결된다 → §10.
- ⚠️ **`PAYMENT_COMPLETED` → `PURCHASED` 전이 트리거가 문서에 없다.** `completeProductGrant` 호출이
  트리거인지 확인 문장이 없다.
- ⚠️ **이 API 가 구독 주문에도 적용되는지 명시가 없다.**
- ⚠️ **`MINIAPP_MISMATCH` 와 `NOT_FOUND` 의 구분 기준**, `ERROR` 에서 재시도해야 하는지도 미명시.

### 4-2. mTLS

- 모든 파트너 서버 API 는 **mTLS 클라이언트 인증서로 호출 주체를 식별**하고, **인증서 CN 으로 미니앱을 구분**한다.
  mTLS 는 TLS 핸드셰이크 단계에서 이뤄져 **요청 헤더에 나타나지 않는다.**
- 무중단 교체를 위해 **인증서를 두 개 이상 등록**해 둘 수 있다.
- ⚠️ **발급 절차 공식 문서가 유실 상태다.** `server-api.md` → “서버 mTLS 인증서 발급받기 문서”(링크 없음),
  `hash-key.md` → getting-started, getting-started → `server-api.md` 로 **순환 참조**이며,
  토스 직원이 안내한 `/development/integration-process.html` 은 **현재 404** 다.
- 커뮤니티 단서(공식 아님): 콘솔 ‘mTLS 인증서 > 발급받기’, **PEM 형식**, CN = appName,
  Issuer = `Toss appsintosp Root CA (O=Viva Republic)`, 한 사례에서 유효기간 약 13개월.
  ⚠️ **PKCS12 제공 여부·CSR 주체·개인키 암호·유효기간 정책은 전부 미확인.**
- ⚠️ 미니앱 `appName`(앱 스킴)은 **한 번 등록하면 변경할 수 없고**, 형식 규칙을 어기면 인증서 발급이 실패할 수 있다.
- ⚠️ **파트너 API 의 권장/최대 타임아웃 값이 문서 어디에도 없다.**

### 4-3. 방화벽

| 방향 | 대상 |
|---|---|
| **Inbound** (앱인토스 → 우리 서버, 웹훅 수신) | `117.52.3.11` · `211.115.96.11` · `106.249.5.11` · `117.52.3.80~87` · `211.115.96.80~87` · `106.249.5.80~87` — **모두 TCP 443** |
| **Outbound** (우리 서버 → 앱인토스) | `apps-in-toss-api.toss.im`: `117.52.3.192` · `211.115.96.192` · `106.249.5.192` |

- ⚠️ **Inbound 는 6개 엔트리 전부** 열어야 한다. 하나만 열면 나머지 5개 출발지의 웹훅이 차단된다.
- ⚠️ **Outbound 표에 “인앱결제(IAP)” 행이 없다.** IAP OpenAPI 의 `servers` 값으로 추정할 뿐인데,
  **토스페이 spec 도 문자열까지 동일하게 `apps-in-toss-api.toss.im` 을 선언**하면서 실제 방화벽 표는
  토스페이를 `pay-` 도메인으로 지정한다 — 즉 `servers` 는 **전 spec 에 복사된 보일러플레이트**이고
  실제 호출 호스트의 근거가 되지 못한다. **채널톡 확인 필요.**

---

## 5. 웹훅 (결제 알림 URL) — **구독 전용**

### 5-1. 등록

- 콘솔의 인앱 상품 등록 흐름 **4단계 ‘결제 알림 URL 등록하기’** 에서 등록한다.
- **서버 URL + 선택적 Basic Auth 헤더 값**을 입력한다.
- ⚠️ 콜백 URL 이 미니앱 단위인지 상품 단위인지, 여러 개 등록 가능한지,
  **개발/운영 환경별로 분리 가능한지 명시가 없다.**

### 5-2. 이벤트는 정확히 2종

| eventType | 언제 | 페이로드 |
|---|---|---|
| `callback.registration_verification` | 콜백 URL 등록·변경 시 | `{ eventType, occurredAt }` **2개 필드뿐. challenge·에코백 값 없음** |
| `subscription.status_changed` | 구독 상태 변경 확정 시 | 아래 |

```json
{
  "eventType": "subscription.status_changed",
  "eventVersion": "1.0",
  "occurredAt": "2026-05-06T00:00:00",
  "orderId": "order-1",
  "sku": "premium.monthly",
  "changeReason": "RENEWED",
  "subscription": {
    "previous": { "status": "...", "accessGranted": true, "expiresAt": "...", "autoRenew": true },
    "current":  { "status": "...", "accessGranted": true, "expiresAt": "...", "autoRenew": true }
  }
}
```

- `changeReason` **12종**: `CREATED` · `RENEWED` · `RECOVERED` · `RESTARTED` · `ENTERED_GRACE_PERIOD` ·
  `ON_HOLD` · `PAUSED` · `AUTO_RENEW_ENABLED` · `AUTO_RENEW_DISABLED` · `EXTENDED` · `EXPIRED` · `REVOKED`
- `subscription.previous` 는 **optional**, `current` 는 필수.
- ⚠️ **스냅샷 필드명이 SDK 와 다르다** — 웹훅은 `accessGranted`/`autoRenew`, SDK 는 `isAccessible`/`isAutoRenew`.
- ⚠️ **웹훅 스냅샷에는 `gracePeriodExpiresAt` 과 `catalogId` 가 없고**, SDK 응답에는 `changeReason` 이 없다.
  → **두 소스를 하나의 모델로 합치면 `gracePeriodExpiresAt` 은 웹훅만으로 절대 채울 수 없다.**
- ⚠️ **페이로드에 사용자 식별자가 없다.** `orderId` 뿐이므로 **서버가 `orderId ↔ 익명키` 매핑을
  미리 저장해 두지 않으면 웹훅을 사용자에게 연결할 수 없다.**
- ⚠️ **페이로드에 `subscriptionId`·`catalogId` 가 없다.** SDK 의 `processProductGrant` 는 `subscriptionId` 를,
  `getSubscriptionInfo` 는 `catalogId` 를 주는데 **세 식별자의 관계가 문서에 정의돼 있지 않다.**
- ⚠️ **단건(소모품) 결제의 웹훅은 없다.** 문서화된 이벤트 2종은 **둘 다 구독 스코프**다.

### 5-3. 진위 검증 · 응답 규격

| 항목 | 상태 |
|---|---|
| 검증 수단 | **콘솔에 등록한 선택적 Basic Auth 헤더**가 유일. `Authorization: Basic {값}` 으로 실려 온다. 콘솔에는 원문을 넣고 **전달 시 base64 인코딩**된다(토스 직원 Dylan 확인) |
| HMAC·서명 헤더 | ❌ **문서·커뮤니티 어디에도 없다** |
| 두 이벤트의 인증 | **동일**하다(Dylan 확인) — 같은 검증 로직을 태우면 된다 |
| 서버 응답 코드 | ⚠️ 문서는 *“이 이벤트를 정상 수신해야 콜백 URL이 활성화돼요”* 라고만 한다. 토스 직원(Dylan)이 **204 로 보내라**고 답한 것이 유일한 근거 |
| **재전송 정책** | ⚠️ **문서·커뮤니티 어디에도 없다.** 재시도 여부·횟수·백오프 전부 미확인 |
| **멱등 이벤트 ID** | ⚠️ **없다.** `eventType + occurredAt + orderId` 조합 외에 중복 판별 키가 없다 |

- 시각 값은 **timezone 없는 ISO-8601**(`"2026-05-06T00:00:00"`). ⚠️ **기준 타임존을 토스가 확답하지 않았다**
  (2026-08-06 스레드에서 되묻는 중). UTC 인지 KST 인지 미확정 — **“KST 로 해석하라”는 문서 서술은 없다.**

---

## 6. 환불 — 우리는 처리할 수 없다

| OS | 누가 결정하나 | 파트너가 할 수 있는 것 |
|---|---|---|
| **iOS** | **Apple 전권** | ❌ 승인·거절 권한 **없음**. *“파트너사는 결제 상태 조회 API로 상태만 확인할 수 있어요.”* |
| **Android** | 사용자가 토스 앱에서 요청 → **파트너사가 콘솔 ‘환불 내역’ 에서 승인/반려** → **최종 결정은 Google Play** | 승인/반려 |

- **파트너 서버가 환불을 요청·처리하는 API 는 없다.** (토스페이의 `refund-payment`/`refund-billing` 은
  **IAP 가 아니라 토스페이 전용**이다.)
- 환불 감지 경로가 **상품 유형별로 다르다**:

| 상품 | 감지 경로 |
|---|---|
| **구독** | 웹훅 `changeReason: REVOKED` **푸시** |
| **단건(소모품)** | ⚠️ **푸시 없음.** 서버 `get-order-status` 폴링(`REFUNDED`) 또는 클라 `getCompletedOrRefundedOrders`(`status:'REFUNDED'`) |

- ⚠️ 부분 환불·비례 정산 규칙은 문서에 없다.
- ⚠️ **파트너가 호출할 수 있는 구독 해지 API 도 없다** — 해지는 사용자가 구입한 스토어에서 직접 해야 한다
  (토스 직원 Dylan, 2026-06-16). 미니앱 안에서 해지 경로를 안내하는 방법도 문서에 없다.

---

## 7. ⚠️ 익명키와 결제의 관계 — 이 프로젝트의 최대 쟁점

우리는 토스 로그인을 쓰지 않는다. 그런데 결제 문서 곳곳이 로그인을 전제한다.

### 7-1. 상충하는 근거들

| # | 근거 | 방향 |
|---|---|---|
| ① | 인앱결제 가이드 **두 곳**: *“결제 상태 조회 API 사용을 위해서는 **반드시** 토스 로그인 연동을 먼저 진행해 주세요.”* | ❌ 로그인 필요 |
| ② | `get-order-status` **OpenAPI spec**: `parameters: []`, 보안 스킴 `mutualTLS` 단독 | ✅ mTLS 만으로 가능 |
| ③ | 가이드의 헤더 표: `x-toss-user-key` **필수 여부 N(선택)**, *“헤더를 포함하지 않으면 모든 주문 건이 응답돼요”* | ✅ 가능 |
| ④ | 토스 직원 Dylan: *“서버간 통신은 mTLS 인증서를 통해 진행되는데, 파트너 인증은 해당 mTLS 인증서로 진행합니다.”* / *“다른 앱의 주문 조회는 불가능합니다”* ([4369](https://techchat-apps-in-toss.toss.im/t/api-get-order-status/4369)) | ✅ 가능 |
| ⑤ | 토스 직원 Dylan (2026-01-16): **인앱 결제에 토스 로그인이 필수가 아니다**라고 명시 | ✅ 가능 |

- `orderId` 는 **요청 바디의 필수 필드**다. 따라서 `x-toss-user-key` 를 생략해도
  “모든 주문 건이 응답”은 **`orderId` 로 특정된 건에 한정된 서술**로 읽어야 한다.
- ⚠️ **`x-anon-key`(익명키)로 주문 소유자를 한정할 수 있다는 문서 근거는 없다.**
  파트너 API 인증 문서는 엔드포인트에 따라 `x-toss-user-key` / `x-anon-key` / `Authorization Bearer`
  중 하나를 보낸다고 일반론을 서술하지만, **`get-order-status` 문서에는 `x-anon-key` 가 나오지 않는다.**
- ⚠️ hash(익명키) 인증을 지원하도록 **확장된 API 는 프로모션·스마트발송·토스페이 3종**이고
  **IAP 는 그 목록에 없다.** 공식 블로그 본문도 익명키로 가능한 것으로 스마트발송·프로모션·토스페이
  3가지를 들 뿐 **인앱결제는 언급조차 없다.**
- ⚠️ `getAnonymousKey` 레퍼런스는 반환 키가 **토스 서버 API 호출용이 아니며 내부 사용자 식별·데이터 관리
  용도로만** 쓰라고 경고한다 → **[CLAUDE.md](../../CLAUDE.md) 의 “hash 인증으로 토스 서버 API 도 호출한다
  — 인앱결제 지원” 서술과 충돌한다.**

> **결론**: `get-order-status` 는 **`orderId` + mTLS 만으로 호출 가능한 것으로 보이지만**,
> 가이드의 “반드시 토스 로그인” 문구가 살아 있어 **서면 확인 없이는 확정할 수 없다.**
> 이 답이 “불가”면 **서버 결제 검증 경로가 0이 되고 단건 환불 감지 수단도 함께 사라진다.**

### 7-2. 기기 변경 — 검수 항목과 직결

체크리스트 원문: *“토스 앱에 로그인된 기기를 변경해도, 기존에 결제한 인앱 결제 데이터(이용권 등)가 유지돼요.”*

IAP 문서가 제시하는 수단은 **① 네이티브 저장소 활용 ② 토스 로그인 연동 + 인앱결제 상태 조회 API 활용** 둘이다.
우리는 둘 다 안 쓴다. 대신 익명키에 기댄다 — 그런데 **토스 직원 답변이 시점에 따라 엇갈린다**:

| 시점 | 답변 |
|---|---|
| 2026-01-16 (Dylan) | 토스 로그인을 빼면 **기기 변경 시 인앱결제 상품이 유지되지 않아** CS 인입 소지가 크다. 별도 storage 제공을 논의 중(미출시) |
| **2026-07-27 (Dylan)** | **익명키 hash 는 토스앱 재설치·기기 변경에도 동일하게 유지된다.** 저장 스키마는 **64자**면 충분 |

- **최신(07-27) 답변이 우세**하지만 상충 자체는 남아 있다.
  ⚠️ `hash-key` 레퍼런스 문서가 보장하는 문장은 *“같은 미니앱 안에서 동일한 사용자에게 항상 같은 값이
  반환돼요”* 와 *“미니앱별로 고유”* 뿐이고, **기기 변경·재설치를 명시한 1차 문서 문장은 없다.**
- ⚠️ **토스 완전 탈퇴 후 재가입하면 hash 가 새로 발급**되어 이용권이 승계되지 않는다.
- ⚠️ **미니앱이 바뀌면 익명키를 유지할 수 없다**(직원 seonjeong, 2026-07-27).
  *“토스 로그인을 쓴 경우에만 매핑이 가능하며, 기존 결제 사용자에게 새 미니앱에서 혜택을 승계하는 지원은 어렵다.”*
- ⚠️ 익명키 체계에는 토스 로그인의 **‘연결 끊기 콜백’에 해당하는 탈퇴 통지 수단이 없다** —
  탈퇴 사용자 데이터 정리를 서버가 감지할 방법이 없다.

---

## 8. 샌드박스 · 테스트

| 대상 | 샌드박스 |
|---|---|
| 일회성 결제(소모품) | ✅ 지원 |
| **자동갱신 구독** | ❌ **미지원** — *“현재 샌드박스 앱에서는 구독 기능 테스트를 지원하지 않아요. 추후 지원 예정이에요.”* |
| `getAnonymousKey` | ⚠️ **mock 반환** — `anon-key/verify` 검증 불가. E2E 는 실기기(QR) 필요 |

**필수 테스트 시나리오 4가지** (체크리스트 ✔️ 항목):

1. **상품 목록 노출** — 콘솔 등록 상품이 정상적으로 내려오는지
2. **결제 성공**
3. **결제 성공 + 서버 지급 실패** — `getPendingOrders` 복원 → `completeProductGrant` 마감 → 사용자 안내
4. **에러 테스트 4종** — 네트워크 오류 / 사용자가 결제 취소 / 내부 오류 / 파트너사 상품 지급 실패

(‘주문 상태 조회 API’는 **권장**이며 필수가 아니다.)

- ⚠️ **샌드박스와 라이브는 CORS·네트워크 동작이 다르다** — 실환경 재검증이 명시적으로 요구된다.
  CORS 허용 오리진(SDK 3.x): 운영 `https://<appName>.web.tossmini.com`,
  콘솔 QR 테스트 `https://<appName>.private-web.tossmini.com` — **둘 다 등록해야 한다.**
  (SDK 1.x~2.x 는 `<appName>.apps.tossmini.com` 계열로 **도메인이 완전히 다르다.**)
- ⚠️ QR 테스트 실행 조건 3가지: 토스 앱 로그인 / 워크스페이스 멤버 / **만 19세 이상**.
- ⚠️ **QR 테스트에서 실결제가 발생한다** — 토스 직원 Dylan: *“E2E 테스트를 위해 토스앱에서 실 결제가
  진행되어야 합니다.”* (별도 ‘내부 테스터’ 지정 메뉴는 문서에 없다)
- ⚠️ **샌드박스/스테이징 전용 API 호스트도, 테스트 전용 mTLS 인증서 발급 절차도 문서에 없다.**
- 검수는 **영업일 최대 3일**, 카테고리에 따라 7일 이상. 반려 시 콘솔 ‘반려 사유 보기’ → 새 번들 재요청.
  **출시 후에도 사후 검수**가 진행되며 법·정책 위반 시 긴급 운영 중단이 선행될 수 있다.

---

## 9. 검수 체크리스트 — 인앱 결제 9개 항목

비게임 출시 가이드 ‘인앱 결제’ 섹션 전체:

1. 결제 중 **음악·영상 재생 일시정지**(결제 종료 후 자동 재개)
2. 주문 금액과 **구글/애플 결제창 금액 일치**
3. 정상 결제(구글 결제 테스트 환경 포함)
4. 결제 후 **복귀 시 결과 반영**
5. 결제창 취소 시 **주문 화면 복귀**
6. **결제 실패 사유 인지**
7. 결제 취소 정상 처리
8. **결제 내역 사용자 확인 가능**
9. **기기 변경 시 결제 데이터 유지** (→ §7-2)

**그 밖의 결제 관련 규제**

- ⚠️ **상품명 과장 금지 — 토스가 직접 든 반례가 “무제한”이다.** 이용 기간이 정해진 상품에
  ‘무제한’ 표기를 금지한다. **월 구독의 혜택이 “무제한”인 우리는 상품명 문구를 재검토해야 한다.**
- **현금성·환가성 상품, 토스 포인트 결합 상품은 판매 불가.** 미니앱 내에 현금·유사 자산의
  직접적인 교환·전환·환불 기능이 포함되면 **등록 자체가 불가**(자금세탁 악용 우려)
  → **이용권 잔액을 현금으로 환급하는 설계는 금지 대상이다.**
- **다크패턴 금지** — *“아래 사례들은 이 기준을 벗어난 치명적인 사용성 오류로, 앱인토스 서비스로
  출시할 수 없는 경우에 해당해요.”* 5가지 중 *‘나갈 수 있는 선택지가 없는 경우’* 와
  *‘CTA 버튼만 보고 다음 행동을 예상할 수 없는 경우’* 는 **결제 유도 화면 설계에 직접 걸린다.**
- 인터랙션 반응 **2초** 기준: *“스크롤, 터치, 화면 전환 등 인터랙션 반응이 2초 이상 지연되지 않아요.”*
  ⚠️ 결제 흐름에 어떻게 적용되는지 별도 설명·측정 기준은 문서에 없다.
- 상품 이미지 **1024×1024px**, 저작권은 파트너사가 확보. 이벤트성 문구엔 기간 병기.
- ⚠️ **유료 결제의 환불 정책·청약철회 고지 의무는 특정 카테고리(웹보드·소개팅·중고거래·채팅)에만**
  규정돼 있고 일반 비게임 미니앱 공통 요건으로는 규정돼 있지 않다. 약관 등록 의무는
  **토스 로그인 가이드 안에** 있어 익명키 전용 미니앱에도 적용되는지 불분명하다
  (2026-04-13 커뮤니티 질문에 토스 직원 답변 없음).
- ⚠️ 체크리스트 항목 뒤의 ` (#)` 는 링크가 아니라 텍스트 잔여물이며,
  **각 항목의 세부 판정 기준은 문서로 공개돼 있지 않다.**

---

## 10. ⚠️ 문서에 없는 것 (설계 전 확인 필요)

우선순위 순. **①②는 서면 확인 전에 구독 계약을 확정하면 안 된다.**

| # | 모르는 것 | 왜 치명적인가 |
|---|---|---|
| ① | **갱신 시 `orderId` 가 유지되는가** | 웹훅에 사용자 식별자가 없어 `orderId` 가 유일한 연결고리다. 갱신마다 새 `orderId` 면 **매핑이 갱신 시점에 끊긴다.** 웹훅 예시는 같은 `order-1` 로 `RENEWED` 를 보내지만 **명시 규정이 아니다** |
| ② | **`get-order-status` 를 로그인 없이 호출 가능한가** | §7-1. 불가면 **서버 결제 검증 경로가 0** |
| ③ | **어느 `status` 에서 지급 확정인가**(`PAYMENT_COMPLETED` vs `PURCHASED`) | 소모품에서 틀리면 **이중 지급 또는 지급 누락** |
| ④ | **30초 타이머의 기점** + 구독 적용 여부 | 백엔드 지급 API 의 타임아웃 예산을 못 잡는다 |
| ⑤ | **`processProductGrant` ↔ `completeProductGrant` 선후 관계** | 공식 예제가 후자를 호출하지 않는다 |
| ⑥ | **`completeProductGrant` 중복 호출 멱등성** | 횟수권 카운터를 쓰면 여기서 **무료 이용권이 샌다** |
| ⑦ | **웹훅 재전송 정책** | 유실 시 서버 단독 복구 경로가 있는지 판단 불가 |
| ⑧ | **웹훅 기준 타임존** | 만료 판정이 최대 9시간 어긋난다 |
| ⑨ | **`isAccessible` 산식**, `IN_GRACE_PERIOD`/`ON_HOLD`/`PAUSED` 의 개폐 | 게이트 판정 기준 |
| ⑩ | **`getPendingOrders` 보관 기간**, 미결 주문의 자동 환불 여부 | 복원 유효 윈도우 |
| ⑪ | **`getPendingOrders` 응답에 단건/구독 구분 필드가 있는가** | `Order = { orderId, sku, paymentCompletedDate? }` 뿐. **복원 시 어느 지급 경로로 보낼지 결정 불가** |
| ⑫ | **`orderId` 의 형식·엔트로피** | 서버가 소유자를 확인할 수단이 없어 **`orderId` 가 사실상 bearer 토큰**이 된다. 짧거나 순차적이면 지급 엔드포인트가 브루트포스 표면이 된다 |
| ⑬ | 상품 **가격 변경 절차**, 기존 구독자 영향, 상품 삭제·교체 | 2,200/3,960 을 코드에 상수로 박으면 안 되는 이유 |
| ⑭ | `sku` **명명 규칙**(자동 발급인지 직접 입력인지·포맷·길이) | 하드코딩 가능 여부. **커뮤니티 사례로도 확인 실패** |
| ⑮ | 인앱 **상품 등록 심사** 여부·기간 | 일정. 문서가 명시하는 건 ‘정산 정보 검토 2~3영업일’뿐 |
| ⑯ | mTLS 인증서 **형식·유효기간·갱신·발급 절차** | §4-2 |
| ⑰ | 파트너 API **권장 타임아웃**, QPM 이 엔드포인트별인지 합산인지 | connect/read timeout 근거 없음 |
| ⑱ | 최초 `CREATED` 시 `expiresAt = null` 이 정상인가 | 커뮤니티 보고 있음. 토스는 “가이드 업데이트”로만 답 |
| ⑲ | 연령 제한·결제 한도·1인 구매 제한 (실서비스 정책) | QR 테스트의 ‘만 19세’만 확인됨 |

**보고된 이상 동작 (토스 직원 확인 없음)**

- `RESTARTED` 웹훅을 받아도 `current.autoRenew` 가 `false` 로 유지된 사례
  → **자동갱신 재개를 `changeReason` 만으로 판정하면 안 되고 `autoRenew` 값을 함께 봐야 한다.**
- 월간↔연간 **플랜 전환 시 기존 구독이 살아 있는 채 신규 구독이 동시 활성화되어 중복 결제**된 사례.
  **지연 전환(deferred plan change)·구독 교체 기능은 문서에 없다.**

---

## 11. ⚠️ 문서 간 상충 (구현 시 어느 쪽을 믿을지 정해야 함)

| 항목 | 상충 | 권장 |
|---|---|---|
| `getPendingOrders` 정의 | 공통 문서 “결제 완료·**미지급**” vs SDK 레퍼런스 “아직 **결제가 완료되지 않은**” | 공통 문서(실제 동작) |
| `completeProductGrant` 최소 버전 | 같은 문서 안에서 5.231.0 vs 5.233.0, SDK 레퍼런스 5.233.0 | **5.233.0** (보수적) |
| `completeProductGrant` 반환 | SDK `Promise<boolean>` vs 공통 `Promise<boolean \| undefined>` | **`undefined` 방어** |
| `getCompletedOrRefundedOrders` 시그니처 | 공통 `params?: { key? }` vs SDK “파라미터 없음” | **웹 SDK 는 파라미터 없음** (SDK 레퍼런스가 명시적으로 해소) |
| `getPendingOrders` 의 `paymentCompletedDate` | 인터페이스 `?`(선택) vs 산문 ‘필수’ vs SDK 타입 비선택 | **선택 필드로 방어** |
| `IapProductListItem` | 3곳이 서로 다름 (§3-2) | 가장 넓은 정의 + `type` 부재 방어 |
| 미지원 버전 동작 | 공통 “`undefined` 반환” vs SDK “`UNSUPPORTED_APP_VERSION` throw” | **둘 다 방어** |
| `getSubscriptionInfo` 반환 | 구독 문서 `\| undefined` (“반환할 수 있어요”) vs SDK 페이지 (“반환해요”) | **`undefined` 방어** |
| 로그인 선행 요구 | §7-1 ①②③④⑤ | **서면 확인 전까지 미확정** |

> 🔎 **이 상충들 중 5~6건은 `@apps-in-toss/web-framework` 의 실제 `.d.ts` 를 받아보면 즉시 닫힌다.**
> 현재 레포에 `frontend/` 가 없어 미설치 상태다. **웹 문서만으로는 더 좁힐 수 없다.**

---

## 12. 출처

**공식 문서**
[인앱 결제 가이드](https://developers-apps-in-toss.toss.im/guide/monetization/in-app-payment) ·
[일회성 결제 개발](https://developers-apps-in-toss.toss.im/documentation/common/monetization/iap/in-app-purchase.md) ·
[구독 결제 개발](https://developers-apps-in-toss.toss.im/documentation/common/monetization/iap/in-app-subscription.md) ·
[IAP SDK 레퍼런스](https://developers-apps-in-toss.toss.im/documentation/sdk/domains-api/iap.md) ·
[IAP 서버 API](https://developers-apps-in-toss.toss.im/documentation/api/iap.md) ·
[서버 API 이용하기](https://developers-apps-in-toss.toss.im/documentation/integration/server-api.md) ·
[파트너 API 인증](https://developers-apps-in-toss.toss.im/documentation/api/auth) ·
[비게임 출시 체크리스트](https://developers-apps-in-toss.toss.im/checklist/app-nongame.html) ·
[사이트맵](https://developers-apps-in-toss.toss.im/sitemap.md)

**토스 직원 답변 (커뮤니티)**
[4369 — get-order-status 인증은 mTLS](https://techchat-apps-in-toss.toss.im/t/api-get-order-status/4369) ·
[4454 — 익명키 지속성·탈퇴](https://techchat-apps-in-toss.toss.im/t/getanonymouskey/4454) ·
[4506 — verify 는 소유권을 보장하지 않음](https://techchat-apps-in-toss.toss.im/t/getanonymouskey-api/4506) ·
[4005 — 구독 결제 후 환불 안내 노출](https://techchat-apps-in-toss.toss.im/t/topic/4005) ·
[4329 — processProductGrant true 인데 30초 환불 안내](https://techchat-apps-in-toss.toss.im/t/ios-webview-processproductgrant-true-ack-30/4329) ·
[3802 — 자동갱신 구독 공지](https://techchat-apps-in-toss.toss.im/t/topic/3802)

> ⚠️ **URL 이 `?ask=` 를 포함한 GitBook AI 답변은 1차 출처가 아니다** — 재현·영속성이 보장되지 않으므로
> 이 문서는 각주로 쓰지 않았다.
