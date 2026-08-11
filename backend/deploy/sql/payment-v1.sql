-- ─────────────────────────────────────────────────────────────────────────────
-- payment 도메인 — 테이블 4개 (docs/domain/payment-design.md §3 · §3-1)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (users — user_id 가 논리 참조한다. 이미 배포됨 = 선행 조건 충족 §3-1)
--            event-publication-v1.sql 은 auth 배포에서 이미 적용됐다 — 다시 만들지 않는다(§3-1)
--
-- ⚠️ **물리 FOREIGN KEY 를 걸지 않는다** — architecture.md "타입화된 기본키" 기본 정책.
--    @ManyToOne 이 없어 Hibernate 가 FK 를 만들지도 검증하지도 않고(validate 는 FK 부재를 못 잡는다),
--    무결성은 UNIQUE 제약 + "지급 전 register 보장"(§5-2)이 담당한다. 참조는 주석(-- users.id)으로만 표기.
-- ⚠️ 테이블명은 **소문자** — 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서 깨지고
--    로컬(Windows/H2)에서는 안 드러난다(auth 구현 라운드 1 실측).
-- ⚠️ user_id · usage_tickets.id 는 전부 BIGINT — 타입 ID(UserId·UsageTicketId)는 자바 쪽 표현일 뿐
--    DDL 은 원시 타입이다(§3-1).
-- ⚠️ boolean 컬럼은 BIT 로 적었다 — Hibernate MySQLDialect 의 기본 매핑. validate 기동으로 대조가
--    필요하면 auth-v1.sql 의 선례처럼 실측 후 이 파일을 정정한다.
-- ─────────────────────────────────────────────────────────────────────────────

-- 주문 원장 — 멱등(U3)·선점(U4)의 유일한 근거. created_at 이 곧 지급 시각이다(§3).
CREATE TABLE payment_orders (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    order_id     VARCHAR(64)  NOT NULL,              -- 토스 주문 ID (uuid v7 — ✅-12). U14 비노출
    user_id      BIGINT       NOT NULL,              -- users.id (물리 FK 없음)
    sku          VARCHAR(128) NOT NULL,              -- 토스 응답의 sku (클라 주장이 아니다)
    product_type VARCHAR(16)  NOT NULL,              -- CONSUMABLE | SUBSCRIPTION
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- 🔴 멱등의 근거 — 동시 지급 경쟁(C1)을 DB 가 최종 판정한다(§6-3). 빼면 이중 지급이 난다.
ALTER TABLE payment_orders
    ADD CONSTRAINT uk_payment_orders_order_id UNIQUE (order_id);

-- 소유 주문 조회용(§3)
CREATE INDEX ix_payment_orders_user_id ON payment_orders (user_id);


-- 횟수권 잔량 — 사용자당 1행(§3).
-- ⚠️ balance 에 CHECK (balance >= 0) 를 걸지 마라(§3-1) — 걸면 잔량 부족이 예외로 튀어
--    §6-4 의 "조건부 UPDATE 0행 → PAY_001" 판정과 경로가 갈린다. 음수 방지는 조건부 UPDATE 로 일원화한다.
CREATE TABLE credit_balances (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id    BIGINT      NOT NULL,                 -- users.id (물리 FK 없음)
    balance    INT         NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- 행 생성 경쟁(C2)의 최종 판정자(§6-4)
ALTER TABLE credit_balances
    ADD CONSTRAINT uk_credit_balances_user_id UNIQUE (user_id);


-- 기간권 — 구독 주문당 1행(§3).
CREATE TABLE subscriptions (
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    created_at               DATETIME(6) NOT NULL,
    updated_at               DATETIME(6) NOT NULL,
    order_id                 VARCHAR(64) NOT NULL,   -- 최초 구독 주문 ID — 웹훅이 이 값으로 찾아온다
    user_id                  BIGINT      NOT NULL,   -- users.id (물리 FK 없음)
    status                   VARCHAR(20) NOT NULL,   -- ACTIVE|EXPIRED|IN_GRACE_PERIOD|ON_HOLD|PAUSED|REVOKED
    expires_at               DATETIME(6) NOT NULL,   -- 최초엔 추정값(결제일+31일 — §4-7-1④)
    auto_renew               BIT         NOT NULL,
    last_webhook_occurred_at DATETIME(6) NULL,       -- null = 웹훅 미수신. ⚠️ recheck 는 갱신하지 않는다(§4-7-1⑦)
    expires_at_estimated     BIT         NOT NULL,   -- true = 웹훅이 아직 한 번도 덮지 않았다(§3)
    PRIMARY KEY (id)
) ENGINE = InnoDB;

ALTER TABLE subscriptions
    ADD CONSTRAINT uk_subscriptions_order_id UNIQUE (order_id);

-- ⚠️ 한 사용자에게 활성 구독은 하나 — 두 번째는 DB 가 거부하고 PAY_003 으로 떨어진다(§3).
--    조용히 두 개를 갖는 것(중복 결제 사례 보고됨)보다 낫다.
ALTER TABLE subscriptions
    ADD CONSTRAINT uk_subscriptions_user_id UNIQUE (user_id);


-- 소모 예약 — ✅-4ⓐ "생성 시 예약 → 완료 확정"의 실체(§3).
-- 정리 배치를 만들지 않는다 — 소모 이력은 U8 분쟁 대응에 쓸모가 있다(§6-6).
CREATE TABLE usage_tickets (
    id         BIGINT      NOT NULL AUTO_INCREMENT,  -- 자바 쪽은 UsageTicketId 로 타입화(§3)
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id    BIGINT      NOT NULL,                 -- users.id (물리 FK 없음)
    source     VARCHAR(16) NOT NULL,                 -- SUBSCRIPTION(차감 없음) | CREDIT(차감 1)
    status     VARCHAR(16) NOT NULL,                 -- RESERVED | COMMITTED | RELEASED
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX ix_usage_tickets_user_id_status ON usage_tickets (user_id, status);
