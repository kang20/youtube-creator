-- ─────────────────────────────────────────────────────────────────────────────
-- credit 도메인 — 횟수권 잔량 (backend/docs/new-domain/payment.md 횟수권 애그리거트)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (user_id 가 users.id 를 가리킨다 — 주석으로만 표기)
--            event-publication-v1.sql (잔량 증가의 트리거가 payment 의 ConsumableGranted 이벤트다 —
--            발행 기록이 event_publication 에 남아야 유실이 안 난다)
--
-- 🔴 uk_credit_balance_user_id 가 "잔량은 사용자당 한 행" 불변식의 근거이자 **동시 첫 지급 경쟁의
--    심판**이다. 삽입에 진 쪽은 승자 행에 원자 UPDATE 로 합류한다(UniqueRace).
--    이 테이블의 UNIQUE 제약은 이것 하나여야 한다 — 둘 이상이면 경쟁 판정이 엉뚱한 위반을
--    성공으로 오판한다(architecture.md "동시성" 규칙 5).
--
-- ⚠️ **CHECK 제약을 걸지 않는다.** 음수 금지는 갱신 조건(WHERE 절)이 지킨다 — 저장소 제약으로
--    걸면 잔량 부족이 오류로 튀어 정상 판정 경로와 갈라진다(payment.md 횟수권 규칙).
--    증가 경로(balance = balance + 1)는 음수를 만들 수 없고, 소모 경로는 아직 범위 밖이다.
--
-- ⚠️ **user_id 에 FK 를 걸지 않는다.** credit 는 auth 의 User 엔티티를 모르고, 참조는 타입 ID
--    값 컬럼(BIGINT)으로만 한다. 무결성은 UNIQUE 가 담당한다(모듈 경계를 DB 까지 관철).
--
-- ⚠️ 테이블·컬럼명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서
--    깨지고 로컬(Windows/H2)에서는 드러나지 않는다.
--
-- 행이 없는 것과 잔량 0 은 **같은 뜻**이다 — 결제한 적 없는 사용자에게는 행이 없다.
-- order_id 를 저장하지 않는다 — 자연키 복제 금지. "같은 주문으로 두 번 오르지 않는다"는
-- 발행 측(orders 원장의 UNIQUE(order_id) 멱등)이 지킨다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE credit_balance
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL COMMENT 'users.id — FK 없음(모듈 경계). 잔량은 사용자당 한 행',
    balance    BIGINT      NOT NULL COMMENT '잔량(개수, 금액 아님). 음수 금지는 CHECK 가 아니라 갱신 조건이 지킨다',
    created_at DATETIME(6) NOT NULL COMMENT '첫 지급 일시',
    updated_at DATETIME(6) NOT NULL COMMENT '마지막 증감 일시 — 원자 UPDATE 가 직접 갱신한다(Auditing 미경유)',
    PRIMARY KEY (id),
    CONSTRAINT uk_credit_balance_user_id UNIQUE (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '횟수권 잔량 — 사용자당 한 행, 동시 첫 지급의 심판은 UNIQUE(user_id)';
