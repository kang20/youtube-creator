# CLAUDE.md — ytcreator (ytcreator)

> 이 파일은 Claude Code 세션 시작 시 **자동으로 로드**됩니다.

## 프로젝트 한 줄 요약

<!-- TODO: 이 앱이 뭔지 한 줄. 토스 미니앱(Apps In Toss) 기준으로 -->

## 핵심 컨셉 (3줄)

1. <!-- TODO -->
2. <!-- TODO -->
3. <!-- TODO -->

## 레포 구조

```
ytcreator/
├── frontend/                  토스 미니앱 클라 (Vite + React + @apps-in-toss/web-framework)
├── backend/                   API 서버 (Spring Boot + Spring Modulith)
├── docs/
│   ├── prd.md                 제품 전체 PRD
│   ├── client/prd-client.md   클라 PRD
│   ├── server/api-spec.md     REST API 명세 (정본 — 프론트/백 계약)
│   └── platform/              Apps In Toss 플랫폼 레퍼런스
├── CLAUDE.md                  ← 지금 이 파일
└── README.md
```

## 인증/식별 모델

**이 서비스는 토스 로그인을 쓰지 않는다. 전 구간 익명키 단일 식별.**

| 용도 | 수단 | 로그인 |
|---|---|---|
| 전 구간 (일반 기능 · 알림 · 인앱결제) | `User.getAnonymousKey()` → `X-Anonymous-Key` 헤더 | ❌ |
| 서버→토스 API (프로모션·스마트 발송·토스페이 3종) | `x-anon-key` 헤더 (hash 인증) | ❌ |
| 서버→토스 IAP (`get-order-status`) | **mTLS + `orderId` 단독** — `x-anon-key` 를 쓰지 않는다 | ❌ |

- 익명키는 **미니앱마다 고유**하고, **같은 사용자는 같은 미니앱에서 항상 같은 값**을 받는다.
  기기가 아니라 **토스 계정 기준**이므로 기기 변경·재설치에도 유지된다.
- **hash 인증으로 토스 서버 API 도 호출한다** — 대상은 **프로모션·스마트 발송(알림)·토스페이 3종**.
  ⚠️ **인앱결제는 여기 없다** — IAP 서버 API 는 mTLS 단독이다(payment.md §9-1 확정).
- ~~발급값은 서버에서 anon-key verify API 로 검증한다~~ — **verify 는 폐기됐다**(2026-08-07 결정).
  소유권을 증명하지 못해 실효가 없다 → `backend/docs/rule/toss-integration.md`.
- ⚠️ **미니앱이 바뀌면 익명키도 바뀐다.** 앱을 새로 출시하면 승계되지 않는다 — 데이터 이관 설계 필요.
- 토스 로그인은 "앱인토스 밖 계정과 같은 사람인지 연결"할 때만 필요하다. 우리는 해당 없음.
- 진입 마찰이 곧 이탈이다. 로그인을 붙이려면 그 자체가 기획 결정이어야 한다.

## 규칙 — 반드시 지켜야 할 것

- **한글로 대답**한다
- **MVP 범위만 집중**한다
- **클라/서버 경계를 지킨다** — 서버 계약 변경은 `docs/server/api-spec.md` 에 먼저 반영
- **브랜치/커밋 규칙(`.githooks/pre-commit`)**: `main`=문서·`assets/`만, 코드는 `frontend`/`backend`
  브랜치에서(서로 상대 디렉토리 변경 금지), 커밋 전 origin 최신화 필수.
  클론 후 `git config core.hooksPath .githooks` 1회 필요
- 문서의 **🔶 표시는 미확정 결정** — 임의 확정 말고 사용자에게 확인
