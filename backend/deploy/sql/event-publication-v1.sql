-- ─────────────────────────────────────────────────────────────────────────────
-- 모듈 이벤트 아웃박스 (Spring Modulith 2.0.7 · spring-modulith-starter-jpa)
--
-- 적용 시점: 앱 첫 배포보다 **먼저**. (docs/domain/auth-design.md §3-1)
-- 대상 DB  : ytcreator (MySQL 8.0)
--
-- ⚠️ 이 테이블은 auth 의 테이블이 아니지만 **이번 배포의 선행 조건**이다.
--    spring-modulith-starter-jpa 가 DefaultJpaEventPublication 을 JPA 엔티티로
--    무조건 등록한다(JpaEventPublicationAutoConfiguration @AutoConfigurationPackage).
--    운영은 ddl-auto=validate 이므로 **어떤 모듈도 이벤트를 발행하지 않아도**
--    이 테이블이 없으면 기동이 실패한다.
--
--    실측(2026-08-07, 이 프로젝트 의존성 그대로 기동):
--      Schema validation: missing table [event_publication]
--      → 기동 실패 확인됨. "이벤트를 안 쓰니 무관하다"는 판단은 틀렸다.
--
-- ⚠️ 테이블/컬럼 이름은 **소문자**다. 엔티티의 @Table 은 EVENT_PUBLICATION 이지만
--    Spring Boot 기본 물리 네이밍 전략(PhysicalNamingStrategySnakeCaseImpl)이
--    소문자로 내린다. 리눅스 MySQL 은 테이블명이 대소문자를 구분하므로
--    대문자로 만들면 validate 가 통과하지 못한다.
--
-- 아래 정의는 이 프로젝트의 실제 매핑(Boot 4.0.6 · Hibernate 7.2.12 · MySQLDialect)에서
-- 생성한 DDL 그대로다 — validate 가 비교하는 대상과 한 글자도 어긋나지 않게 하기 위함이다.
-- (serialized_event 가 varchar(255) 인 것도 Modulith 의 매핑 그대로다. 더 긴 이벤트 본문이
--  필요해지면 매핑 쪽 결정이 먼저다 — 여기서 임의로 TEXT 로 바꾸면 validate 가 깨진다.)
--
-- 아카이브 테이블(event_publication_archive)은 만들지 않는다 —
-- spring.modulith.events.completion-mode=archive 일 때만 엔티티가 등록된다(현재 미설정).
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE event_publication (
    id                     BINARY(16)   NOT NULL,
    completion_attempts    INTEGER      NOT NULL,
    completion_date        DATETIME(6),
    event_type             VARCHAR(255) NOT NULL,
    last_resubmission_date DATETIME(6),
    listener_id            VARCHAR(255) NOT NULL,
    publication_date       DATETIME(6)  NOT NULL,
    serialized_event       VARCHAR(255) NOT NULL,
    status                 ENUM ('COMPLETED', 'FAILED', 'PROCESSING', 'PUBLISHED', 'RESUBMITTED'),
    PRIMARY KEY (id)
) ENGINE = InnoDB;
