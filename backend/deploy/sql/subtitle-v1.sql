-- ─────────────────────────────────────────────────────────────────────────────
-- subtitle 도메인 — 자막 작업 (backend/docs/new-domain/subtitle/subtitle-v1.md 작업 애그리거트)
--
-- 적용 시점: 앱 배포보다 **먼저**. 운영은 ddl-auto=validate 라 스키마가 배포로 만들어지지 않는다.
-- 대상 DB  : ytcreator (MySQL 8.0, 서버 기본 문자셋 utf8mb4 / utf8mb4_unicode_ci)
-- 선행     : auth-v1.sql (user_id 가 users.id 를 가리킨다 — 주석으로만 표기)
--
-- 🔴 version 이 **동시 통지 방어의 근거**다. "모든 조건의 첫 항목이 현재 상태다" 규칙을 낙관적
--    잠금으로 관철한다 — 없으면 동시에 도착한 완료 통지 두 개가 둘 다 통과해 상태가 두 칸 뛴다.
--
-- 🔴 last_transitioned_at 은 **멈춘 작업 판정의 유일한 근거**다. 상태가 바뀔 때만 갱신하며
--    조회로는 절대 갱신하지 않는다 — 조회가 갱신하면 멈춘 작업이 영원히 안 잡힌다.
--
-- ⚠️ expired_at 은 **status 와 다른 축**이다. 상태로 합치면 완료·실패와 배타가 되어
--    "완료됐고 만료된" 작업을 표현할 수 없다. 보관 기간 경과는 이 컬럼만 기록하고 상태는 그대로 둔다.
--
-- ⚠️ **user_id 에 FK 를 걸지 않는다.** subtitle 은 auth 의 User 엔티티를 모르고, 참조는 타입 ID
--    값 컬럼(BIGINT)으로만 한다(모듈 경계를 DB 까지 관철).
--
-- ⚠️ 테이블·컬럼명은 **소문자**로 쓴다. 대문자로 만들면 대소문자를 구분하는 리눅스 MySQL 에서
--    깨지고 로컬(Windows/H2)에서는 드러나지 않는다.
--
-- ⚠️ **저장소 키(*_key)는 위치일 뿐 실물이 아니다.** 실물은 오브젝트 스토리지에 있고 우리 DB 를
--    거치지 않는다. 이 값을 그대로 내려주지 않으며(내려주는 것은 수명이 짧은 링크다) 로그에도 싣지
--    않는다 — 512 는 오브젝트 스토리지 키 상한을 감안한 여유값이다.
--
-- redispatch_count 는 재개 한계(REDISPATCH_LIMIT, 3회) 판정의 유일한 수단이다. 작업당 누적이며
-- 단계별로 리셋하지 않는다 — 초과하면 SERVER_FAULT 로 닫고 이용권을 되돌린다.
-- ix_jobs_user_id 는 작업 목록(복구 장치) 조회 경로가 쓴다.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE jobs
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL COMMENT 'users.id — FK 없음(모듈 경계). 소유자는 한 번 정해지면 바뀌지 않는다',
    status               VARCHAR(32)  NOT NULL COMMENT 'JobStatus — 프론트 화면 분기의 유일한 근거. 값 하나하나가 계약이다',
    source_key           VARCHAR(512) NULL COMMENT '원본 위치. 실물은 오브젝트 스토리지에 있다 — 그대로 내려주지 않는다',
    script_key           VARCHAR(512) NULL COMMENT '대본 위치. 워커가 만들기 전에는 비어 있다',
    subtitle_key         VARCHAR(512) NULL COMMENT '자막 파일 위치. 이 도메인의 유일한 산출물',
    failure_cause        VARCHAR(16)  NULL COMMENT 'FailureCause — 실패했을 때만 채워진다. 이 값 하나가 이용권 회복 여부를 정한다',
    last_transitioned_at DATETIME(6)  NOT NULL COMMENT '마지막 전이 시각 — 멈춘 작업 판정의 유일한 근거. 조회로 갱신하지 않는다',
    expired_at           DATETIME(6)  NULL COMMENT '원본을 지운 시각. status 가 아니라 별도 축이다',
    redispatch_count     INT          NOT NULL DEFAULT 0 COMMENT '재의뢰 누적 횟수 — REDISPATCH_LIMIT(3) 초과 시 SERVER_FAULT 로 닫는다',
    version              BIGINT       NULL COMMENT '낙관적 잠금 — 동시 완료 통지가 상태를 두 칸 뛰게 하는 것을 막는다',
    created_at           DATETIME(6)  NOT NULL COMMENT '작업 생성 일시',
    updated_at           DATETIME(6)  NOT NULL COMMENT '마지막 변경 일시',
    PRIMARY KEY (id),
    INDEX ix_jobs_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT '자막 작업 — 영상 하나를 올려 자막 파일 하나를 받기까지의 처리 단위';
