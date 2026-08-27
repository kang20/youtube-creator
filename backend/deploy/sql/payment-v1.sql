-- ─────────────────────────────────────────────────────────────────────────────
-- payment 도메인 — 주문 원장 (backend/docs/new-domain/payment.md 주문 애그리거트)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (user_id 가 users.id 를 가리킨다 — 주석으로만 표기)
--
-- 🔴 uk_orders_order_id 가 **멱등의 유일한 근거**다. 동시 지급 경쟁을 DB 가 최종 판정한다.
--    이 인덱스를 빼면 같은 주문으로 이용권이 여러 번 지급된다 = 무료 이용권 유출.
--
-- ⚠️ **user_id 에 FK 를 걸지 않는다.** payment 는 auth 의 User 엔티티를 모르고, 참조는
--    타입 ID 값 컬럼(BIGINT)으로만 한다. 모듈 경계를 DB 레벨까지 관철한 결과다.
--    대가로 "users 에 없는 user_id" 를 DB 가 막아주지 않는다 — 인증 게이트가 지킨다.
--
-- ⚠️ **order_id 원문이 이 컬럼에만 남는다.** 응답·로그·예외 메시지에는 나가지 않는다(U14).
--    UNIQUE 위반 시 MySQL 이 `Duplicate entry '<값>' for key ...` 로 값을 로그에 싣는다는 점을
--    알고 있어야 한다 — 지급 경로의 예외 로깅은 원문을 그대로 흘리지 않도록 다룬다.
--
-- ⚠️ 테이블·컬럼명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서
--    깨지고 로컬(Windows/H2)에서는 드러나지 않는다.
--
-- 지급 일시 컬럼을 따로 두지 않는다 — 원장은 생성 이후 변하지 않아 created_at 과 영원히 같다.
--
-- 현재 payment 는 **주문 애그리거트만** 구현돼 있다. 구독·횟수권 잔량·이용 티켓 테이블은
-- 각 애그리거트를 구현할 때 payment-v2 이후로 추가한다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE orders
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    order_id     VARCHAR(64)  NOT NULL COMMENT '토스 주문 식별자(uuid v7). U14 — 밖으로 내보내지 않는다',
    user_id      BIGINT       NOT NULL COMMENT 'users.id — FK 없음(모듈 경계). 선점으로 정해지고 바뀌지 않는다',
    sku          VARCHAR(128) NOT NULL COMMENT '토스가 답한 상품 코드. 클라이언트 주장이 아니다',
    product_type VARCHAR(16)  NOT NULL COMMENT 'CONSUMABLE | SUBSCRIPTION',
    created_at   DATETIME(6)  NOT NULL COMMENT '지급 일시',
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_orders_order_id UNIQUE (order_id),
    INDEX ix_orders_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '주문 원장 — 멱등·선점의 근거';
