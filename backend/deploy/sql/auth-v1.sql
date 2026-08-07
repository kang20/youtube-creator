-- ─────────────────────────────────────────────────────────────────────────────
-- auth 도메인 — 사용자 테이블 (docs/domain/auth-design.md §3 · §3-1 · §3-2)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : event-publication-v1.sql (같은 배포의 선행 조건)
--
-- ⚠️ **익명키 원문을 저장하지 않는다.** 저장 값은 SHA-256(익명키) 의 hex 64자다(§3-2, blockers B4).
--    이유: UNIQUE 위반을 정상 흐름으로 삼는 설계(§6-4)에서 위반이 나면 MySQL 이
--    `Duplicate entry '<값>' for key 'users.uk_users_anonymous_key_hash'` 로 **그 값을 로그에 싣는다.**
--    원문이면 auth.md U6·§4-5 와 docs/ops/logging.md §3.3(anonKey 원문 절대 금지) 위반이다.
--    → 이 컬럼에 원문을 넣는 변경은 그 자체로 U6 위반이다. 되돌리지 마라.
--
-- anonymous_key_hash 의 UNIQUE 인덱스가 **멱등의 유일한 근거**다 — 동시 등록 경쟁을 DB 가 최종 판정한다(§6).
-- 이 인덱스를 빼면 같은 익명키로 사용자가 여러 명 생긴다.
--
-- 길이 64 는 SHA-256 hex 의 **고정 길이**이며 잠정값이 아니다(§12-2 해소됨).
-- 들어오는 원문의 형식 검증 상한(AnonymousKeyFormat.MAX_LENGTH)과는 별개 축이라 같이 움직이지 않는다.
--
-- 아래 정의는 이 프로젝트의 실제 매핑(Boot 4.0.6 · Hibernate 7.2.12 · MySQLDialect)이 생성한 DDL 그대로다.
-- ⚠️ **CHAR(64) 가 아니라 VARCHAR(64) 다.** 설계서 §13 의 표기는 축약이고, 실제로 CHAR 로 만들면
--    validate 가 깨진다 — 실측(2026-08-08):
--      CHAR(64)    → Schema validation: wrong column type encountered in column [anonymous_key_hash]
--                    in table [users]; found [character (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]
--      VARCHAR(64) → 정상 기동
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE users (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    anonymous_key_hash VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

ALTER TABLE users
    ADD CONSTRAINT uk_users_anonymous_key_hash UNIQUE (anonymous_key_hash);
