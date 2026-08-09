---
name: toss-api
description: "앱인토스 공식 문서를 docs MCP로 조회하고 우리 문서와 대조한다. \"토스 API\", \"앱인토스 문서\", \"IAP 스펙\", \"mTLS 스펙\" 요청 시 활성화."
---

# toss-api — 앱인토스 공식 문서 조회

> 토스 플랫폼 최신 스펙을 MCP 로 조회하고 우리 문서와 대조한다.
> 우리 쪽 정본: [docs/platform/iap-spec.md](../../../docs/platform/iap-spec.md) ·
> [backend/docs/rule/toss-integration.md](../../../backend/docs/rule/toss-integration.md)

## 트리거 키워드

`토스 API`, `앱인토스 문서`, `toss api`, `IAP 스펙`, `인앱결제 스펙`, `mTLS 스펙`, `웹훅 스펙`, `SDK 버전`

## MCP 서버

**서버 이름 `docs`** (user 스코프, HTTP). 등록 명령:

```bash
claude mcp add docs --scope user --transport http https://developers-apps-in-toss.toss.im/~gitbook/mcp
```

- 인증 불필요. **세션 도중 추가하면 그 세션에는 도구가 안 붙는다** — 재시작해야 한다.
- 붙었는지 확인: `ToolSearch` 로 `mcp__docs__` 검색. 안 나오면 아래 **폴백**으로 간다.

### 도구 4종 (실제 이름 — 2026-08-09 `tools/list` 로 확인)

| 도구 | 인자 | 반환 | 언제 |
|---|---|---|---|
| `mcp__docs__searchDocumentation` | `{ query }` | `Title` · `Link` · `Content`(**부분**) 목록 | 어느 문서에 있는지 찾을 때 |
| `mcp__docs__getPage` | `{ url }` | 페이지 **전문(마크다운)** | 찾은 문서를 제대로 읽을 때 |
| `mcp__docs__askQuestion` | `{ question, goal? }` | 종합 답변 + 출처 링크 | 답 하나만 필요할 때 |
| `mcp__docs__sendFeedback` | `{ content, pageUrl?, goal? }` | — | 문서 오류·모순을 발견해 신고할 때 |

> ⚠️ 이 스킬의 이전 판이 쓰던 `search_docs` / `get_doc` 은 **존재하지 않는 이름**이다.

## 🚨 제품 3개를 절대 섞지 마라

| 제품 | 무엇 | 우리 것? |
|---|---|---|
| **앱인토스 IAP (인앱결제)** | 미니앱 인앱결제. 앱마켓 결제 위에 얹힌다. `orderId`·`sku`·`processProductGrant`·`get-order-status` | ✅ |
| **토스페이 (TossPay)** | 앱인토스 안의 **다른** 결제 수단. `pay-apps-in-toss-api.toss.im` | ❌ |
| **토스페이먼츠 (TossPayments)** | `docs.tosspayments.com` — **별개 회사 PG.** 앱인토스와 무관 | ❌ |

`paymentKey` · `billingKey` · `결제위젯` · `시크릿키` 가 나오면 **토스페이먼츠 문서를 보고 있는 것**이다. 즉시 버린다.
검수 체크리스트도 “인앱 결제”와 “토스페이”를 **별개 섹션**으로 두므로, 토스페이 항목을 우리에게 적용하지 않는다.

## 핵심 원칙

- **검색 질의는 한국어로 쓴다.** 실측(2026-08-09) — 같은 질문을
  `"인앱결제 주문 상태 조회"` 로 던지면 IAP 문서 5건이 전부 정확히 나오고,
  `"in-app purchase order status API"` 로 던지면 `sdk-3.x`·`getting-started`·`ai-vibe-coding` 같은
  **무관한 문서가 절반 이상** 섞인다. 문서 본문이 한국어라 한국어 질의가 맞다.
- 고유명사·API명은 **원문 그대로** (`IAP`, `getPendingOrders`, `mTLS`, `AdMob`).
- `searchDocumentation` 결과의 `Content` 는 **잘린 미리보기**다. 스펙을 인용할 거면
  **반드시 `getPage` 로 전문을 받아** 확인한다. 미리보기만 보고 계약 문서에 옮기지 않는다.
- **URL 끝에 `.md` 를 붙이면** 마크다운 원문을 받을 수 있다(폴백에서 유용).

## 동작 흐름

### Step 1 — 의도 → 한국어 키워드

| 사용자 의도 | 검색 키워드 |
|---|---|
| 인앱결제 흐름 | `인앱결제`, `상품 지급`, `미결 주문` |
| 정기결제·구독 | `정기결제`, `자동 갱신 구독`, `구독 상태` |
| 결제 웹훅 | `결제 알림 URL`, `구독 상태 변경 콜백` |
| 서버 주문 검증 | `주문 상태 조회`, `파트너 API` |
| mTLS 인증서 | `mTLS`, `서버 인증서`, `상호 인증` |
| 익명키 | `사용자 식별키`, `익명키` |
| 상품 등록·가격 | `인앱 상품 등록`, `공급가`, `수수료`, `정산` |
| 검수·출시 | `출시 검수`, `체크리스트`, `심사` |
| 알림 발송 | `스마트 발송`, `메시지 발송` |
| 광고 | `리워드 광고`, `AdMob` |

### Step 2 — 검색 또는 직접 질문

```
mcp__docs__searchDocumentation({ query: "정기결제 구독 상태 변경 웹훅" })
```

답 하나만 필요하면 `askQuestion` 이 빠르다. **`goal` 을 채우면 답이 우리 상황에 맞춰진다:**

```
mcp__docs__askQuestion({
  question: "구독 갱신 시 orderId 가 최초 주문과 동일하게 유지되나요?",
  goal: "토스 로그인 없이 익명키만으로 구독 소유권을 서버에 매핑하려 한다"
})
```

### Step 3 — 전문 조회

```
mcp__docs__getPage({ url: "https://developers-apps-in-toss.toss.im/documentation/api/iap" })
```

### Step 4 — 우리 문서와 대조

| 조회한 주제 | 대조할 우리 문서 |
|---|---|
| 인앱결제·구독·웹훅·수수료·mTLS·검수 | [docs/platform/iap-spec.md](../../../docs/platform/iap-spec.md) — **플랫폼 사실의 정본** |
| 결제 요구사항·API 계약 | [backend/docs/domain/payment.md](../../../backend/docs/domain/payment.md) |
| 익명키 인증 | [backend/docs/domain/auth.md](../../../backend/docs/domain/auth.md) |
| Spring 구현 규칙 | [backend/docs/rule/toss-integration.md](../../../backend/docs/rule/toss-integration.md) |

> `docs/server/api-spec.md` 는 **아직 존재하지 않는다.** 만들어지면 여기에 추가한다.

**불일치를 발견하면 반드시 사용자에게 보고한다.** 조용히 고치지 않는다 —
`payment.md`·`auth.md` 는 확정 이력이 있는 문서라 변경에 버저닝 규약이 걸린다
(→ [versioning.md](../usecase/references/versioning.md)).

### Step 5 — 🔶 해소 여부 판정

`iap-spec.md §10` 에 **문서에 없는 것 19건**, `§11` 에 **문서 간 상충 9건**이 목록으로 있다.
조회 결과가 그중 하나를 닫으면 **어느 항목인지 번호로 지목**해 보고한다.
`payment.md §9-1` 의 🔶 17건도 같은 방식으로 대조한다.

## 폴백 — MCP 가 안 붙었을 때

세션 도중 서버를 추가했거나 연결이 끊겼으면 대체 경로를 쓴다. 기능은 같다:

| MCP | 폴백 |
|---|---|
| `searchDocumentation` | `WebSearch(allowed_domains=["developers-apps-in-toss.toss.im"])` |
| `getPage` | `WebFetch(url + ".md")` — 마크다운 원문 |
| 문서 목록 | `WebFetch("https://developers-apps-in-toss.toss.im/sitemap.md")` |

- ⚠️ URL 에 **`?ask=` 를 붙인 GitBook AI 답변은 1차 출처가 아니다** — 재현·영속성이 없다.
  계약 문서 각주로 쓰지 않는다.

## 출력 형식

```
## {기능} 앱인토스 문서 조회 결과

### 확인된 사실 (출처 링크 필수)
- {사실} — {URL}
  > "{원문 인용}"

### 우리 문서와 대조
- 일치: ...
- ⚠️ 불일치: {우리 문서}가 {무엇}이라 적었는데 원문은 {무엇}이다
- 🔶 해소: iap-spec.md §10-{N} / payment.md 🔶-{N} 이 닫힌다

### 여전히 문서에 없는 것
- ...
```

## 주의

- **조사 전용 스킬이다.** 코드 작성·문서 수정은 별도 단계다.
- **"문서에 없다"도 결과다.** 추측으로 메우지 말고 없다는 사실을 그대로 보고한다.
- 커뮤니티(`techchat-apps-in-toss.toss.im`)는 **토스 직원 답변만** 2차 출처로 인정한다.
  일반 사용자 글은 "보고된 사례" 수준으로만 다룬다.
- 문서 자체가 틀렸거나 모순되면 **`sendFeedback` 으로 신고**할 수 있다.
  실제로 발견된 것들: mTLS 발급 절차 문서가 순환 참조 + 404,
  `getPendingOrders` 정의가 문서 2곳에서 상반됨 → `iap-spec.md §11`.
