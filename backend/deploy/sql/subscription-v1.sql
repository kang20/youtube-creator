-- ─────────────────────────────────────────────────────────────────────────────
-- subscription 도메인 — 구독 계약 (backend/docs/new-domain/payment.md 구독 애그리거트)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (user_id 가 users.id 를 가리킨다 — 주석으로만 표기)
--            payment-v1.sql (order_id 가 orders.order_id 와 같은 값이다 — 주석으로만 표기)
--            event-publication-v1.sql (구독 개시의 트리거가 payment 의 SubscriptionGranted 이벤트다)
--
-- 🔴 **UNIQUE 는 order_id 하나뿐이다.** 같은 주문의 재지급을 떨어뜨린다 → **멱등 성공**으로 접는다.
--    웹훅이 구독을 찾아오는 유일한 키이기도 하다(플랫폼이 웹훅에 subscriptionId 를 주지 않는다).
--
-- 🔴 **user_id 에 UNIQUE 를 걸지 않는다**(2026-08-16 결정). 구독은 시간축으로 여러 번 생긴다 —
--    만료·해지 뒤 재구독하면 새 주문으로 새 계약이 열린다. user_id 에 UNIQUE 를 걸면 종료된
--    구독 행이 그 자리를 영구히 점유해 **재구독이 영원히 불가능**해진다(행을 지우는 경로가 없고,
--    만료는 status UPDATE 일 뿐이다). UNIQUE 는 status 를 모르므로 "활성은 하나"를 표현하지 못한다.
--    "한 사용자의 현재 계약"은 **가장 최근 행**(id 최대)이고, 개폐 판정은 AccessGate 가 status 로 한다.
--
-- 🔴 **웹훅 반영은 조건부 UPDATE 다.** 순서 판정(last_webhook_occurred_at 비교)을 갱신문 WHERE 절에
--    넣는다 — 읽어서 비교한 뒤 쓰면 동시에 도착한 두 웹훅이 둘 다 통과한다. 웹훅에는 이벤트
--    식별자도 재전송 정책도 없어 발생 시각이 유일한 순서 근거다.
--
-- ⚠️ **CHECK 제약을 걸지 않는다.** 상태 어휘(6종)는 토스가 정하고 늘어날 수 있다 — DB 로 막으면
--    새 어휘가 오는 날 반영이 예외로 튀어 유실이 된다. 모르는 어휘는 애플리케이션이 무시한다.
--
-- ⚠️ **FK 를 걸지 않는다.** subscription 은 auth 의 User 도 payment 의 Order 엔티티도 모르고,
--    참조는 타입 ID 값 컬럼으로만 한다(모듈 경계를 DB 까지 관철).
--
-- ⚠️ **order_id 원문이 orders 에 이어 두 번째로 여기 남는다.** 웹훅이 이 값으로 구독을 찾아오므로
--    마스킹 값으로는 기능이 성립하지 않는다. 알고 여는 것이고, 응답·로그·예외 메시지 비노출은
--    그대로 유지된다(OrderId.toString() 이 강제).
--
-- ⚠️ 테이블·컬럼명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서
--    깨지고 로컬(Windows/H2)에서는 드러나지 않는다.
--
-- expires_at 은 NOT NULL 이다 — 태어날 때 추정값이 들어가고, 이후 모든 갱신 경로가 "비어 오면 기존
-- 값 유지"다. 정본을 무로 덮으면 만료 판정이 통째로 죽는다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE subscriptions
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    order_id                 VARCHAR(64) NOT NULL COMMENT 'orders.order_id — FK 없음(모듈 경계). 웹훅이 이 값으로 구독을 찾는다',
    user_id                  BIGINT      NOT NULL COMMENT 'users.id — FK 없음(모듈 경계). UNIQUE 아님 — 재구독으로 여러 행이 쌓인다',
    status                   VARCHAR(20) NOT NULL COMMENT '토스 어휘 6종: ACTIVE|IN_GRACE_PERIOD|ON_HOLD|PAUSED|EXPIRED|REVOKED. 게이트에 그대로 쓰지 않는다',
    expires_at               DATETIME(6) NOT NULL COMMENT '현재 주기 종료 시각. 최초엔 추정값(결제일+31일)이고 웹훅이 정본으로 덮는다',
    auto_renew               TINYINT(1)  NOT NULL COMMENT '다음 주기 자동 결제 예정 여부',
    last_webhook_occurred_at DATETIME(6) NULL COMMENT '마지막으로 반영한 웹훅의 발생 시각. NULL = 웹훅 미수신. 재확인(recheck)은 이 값을 건드리지 않는다',
    created_at               DATETIME(6) NOT NULL COMMENT '구독 개시 일시',
    updated_at               DATETIME(6) NOT NULL COMMENT '마지막 반영 일시 — 조건부 UPDATE 가 직접 갱신한다(Auditing 미경유)',
    PRIMARY KEY (id),
    CONSTRAINT uk_subscriptions_order_id UNIQUE (order_id),
    INDEX ix_subscriptions_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '구독 계약 — 기간권을 만들어내는 원인. 상태 변화를 아는 경로는 웹훅뿐이다';
