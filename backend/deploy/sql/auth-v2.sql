-- ─────────────────────────────────────────────────────────────────────────────
-- auth 도메인 v4 — refresh 토큰 테이블 (docs/domain/auth-design.md §14-2 · auth.md U9·U10)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (users — user_id 가 논리 참조한다)
--
-- ⚠️ **refresh 원문을 저장하지 않는다**(U10) — 저장 값은 SHA-256(원문) 의 hex 64자다.
--    원문은 발급 응답이 유일하다. 이 컬럼에 원문을 넣는 변경은 그 자체로 U10 위반이다.
-- ⚠️ **물리 FOREIGN KEY 를 걸지 않는다** — architecture.md "타입화된 기본키" 기본 정책
--    (payment-v1.sql 과 같은 규율). 참조는 주석(-- users.id)으로만 표기.
-- ⚠️ 테이블명은 **소문자** — 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서 깨지고
--    로컬(Windows/H2)에서는 안 드러난다(auth 구현 라운드 1 실측).
-- ⚠️ token_hash 는 **CHAR 가 아니라 VARCHAR** — JPA String 매핑의 기대 타입이 VARCHAR 라
--    CHAR 로 만들면 validate 가 거부한다(auth-v1.sql 라운드 3 실측과 동일).
--
-- revoked_at 이 이 설계의 급소다:
--   · NULL       = 활성. 회전(UPDATE ... WHERE token_hash=? AND revoked_at IS NULL)의
--                  영향 행 수가 동시 갱신 경쟁의 판정이다(§14-4) — 락 없음.
--   · NOT NULL   = 회전·전체 폐기로 죽은 토큰. **죽은 행을 지우지 마라** — 재사용 감지(U9)의
--                  근거가 이 행이다. 지우면 재사용이 "미존재"와 구분되지 않는다.
--   정리 배치는 백로그(§14-6) — 만료 14일이 자연 정리한다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id    BIGINT      NOT NULL,                 -- users.id (물리 FK 없음)
    token_hash VARCHAR(64) NOT NULL,                 -- SHA-256(refresh 원문) hex. 원문 비저장(U10)
    expires_at DATETIME(6) NOT NULL,                 -- 발급 +14일(U7)
    revoked_at DATETIME(6) NULL,                     -- NULL = 활성 (위 주석 참조)
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- 조회 키이자 중복 발급 방지 — 발급이 256bit 랜덤이라 충돌은 사실상 없지만, 있다면 여기서 죽는 게 맞다.
ALTER TABLE refresh_tokens
    ADD CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash);

-- 재사용 감지의 전체 폐기(U9 — UPDATE ... WHERE user_id=? AND revoked_at IS NULL)가 이 인덱스를 탄다
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);
