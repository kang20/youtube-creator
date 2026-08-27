-- ─────────────────────────────────────────────────────────────────────────────
-- auth 도메인 — 사용자 권한 컬럼 (Role: USER | ADMIN)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 이 컬럼이 없으면 기동 자체가 실패한다.
-- 대상 DB  : ytcreator (MySQL 8.0)
-- 선행     : auth-v1.sql (users)
--
-- ⚠️ **VARCHAR 다 — MySQL ENUM 타입으로 만들지 마라.** 매핑은 @Enumerated(STRING) 이고,
--    payment-v1.sql 의 status/source 컬럼과 같은 규율이다(그쪽도 VARCHAR 로 validate 를 통과한다).
--
-- ⚠️ **DEFAULT 'USER' 는 기존 행 백필용이자 안전판**이다. 애플리케이션은 항상 값을 채우지만,
--    수기 INSERT 가 권한을 빠뜨렸을 때 떨어질 곳이 ADMIN 이 아니라 USER 여야 한다.
--
-- 운영자 부여는 **승격 API 가 없다 — DB 직접 변경이 유일한 경로**다(의도된 설계).
-- 부여 후 그 사용자의 access 토큰이 만료(최대 30분)되고 refresh 로 갱신돼야 실제로 반영된다 —
-- 권한이 서명된 JWT 클레임이기 때문이다(U8, 요청당 DB 조회 없음).
--   UPDATE users SET role = 'ADMIN' WHERE id = ?;
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE users
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER' AFTER anonymous_key_hash;
