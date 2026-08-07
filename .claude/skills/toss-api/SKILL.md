---
name: toss-api
description: "apps-in-toss MCP로 토스 플랫폼 API 문서 조회 + server-architecture.md 대조. \"토스 API\", \"앱인토스 문서\" 요청 시 활성화."
---

# toss-api — 앱인토스 MCP 문서 조회

> 토스 플랫폼 최신 스펙을 MCP 로 조회하고 우리 설계 문서와 대조한다.
> 연동 규칙: [backend/docs/rule/toss-integration.md](../../../backend/docs/rule/toss-integration.md)

## 트리거 키워드

`토스 API`, `앱인토스 문서`, `toss api`, `토스 로그인 스펙`, `mTLS 스펙`, `IAP 스펙`

## 핵심 원칙

- **MCP 검색은 한국어 키워드 필수** (영어 검색은 결과 빈약 + 토큰 낭비)
- 고유명사/API명은 원문 그대로 (`Button`, `AdMob`, `TossPay`)
- 구현 전 반드시 MCP 최신 스펙 → server-architecture.md 대조

## 동작 흐름

### Step 1: 의도 → 한국어 키워드 변환

| 사용자 의도 | 검색 키워드 |
|------------|------------|
| 토스 로그인 연동 | `토스 로그인`, `로그인 개발` |
| mTLS 인증서 | `mTLS`, `상호 인증` |
| 인앱 결제 | `인앱 결제`, `결제 연동` |
| 알림 발송 | `알림 발송`, `메시지 발송` |
| 광고 | `리워드 광고`, `AdMob` |

### Step 2: MCP 검색

```
search_docs(query="토스 로그인", limit=10)
```

### Step 3: 상세 조회

```
get_doc(id="<검색 결과의 id>")
```

검색 결과는 미리보기(truncated)이므로, 관련 문서는 반드시 `get_doc` 으로 전문 조회.

### Step 4: server-architecture.md 대조

[server-architecture.md](../../../docs/server/api-spec.md) 의 해당 장과 비교:

- 토스 로그인 → 3장
- mTLS → 10장
- IAP → 11장
- 알림 → 12장
- 광고 → 9장

**불일치 발견 시** 사용자에게 보고 (플랫폼 스펙이 바뀌었을 수 있음).

### Step 5: 구현 정보 요약

- 어떤 API/엔드포인트를 호출하는지
- 요청/응답 규격
- mTLS·인증서·토큰 요건
- Spring 구현 시 모듈 internal 어댑터로 감쌀 지점

## 출력 형식

```
## {기능} 토스 API 조회 결과

### MCP 문서 요약
- ...

### server-architecture.md 대조
- 일치/불일치: ...

### Spring 구현 포인트
- 모듈 internal 어댑터: ...
- infra 구현: ...
```

## 주의

- MCP 문서는 한국어. 검색 정확도를 위해 키워드는 간결하게.
- 코드 작성 전 조사 전용. 구현은 별도 단계.
