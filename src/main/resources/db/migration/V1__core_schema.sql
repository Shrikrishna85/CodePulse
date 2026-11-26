CREATE DATABASE IF NOT EXISTS codepulse
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

USE codepulse;

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(40) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    failed_login_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_app_user_username (username),
    UNIQUE KEY uq_app_user_email (email),
    CONSTRAINT chk_app_user_role CHECK (role IN ('STUDENT', 'ADMIN')),
    CONSTRAINT chk_app_user_failed_logins CHECK (failed_login_attempts >= 0)
) ENGINE=InnoDB;

CREATE TABLE problem (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(100) NOT NULL,
    title VARCHAR(160) NOT NULL,
    statement MEDIUMTEXT NOT NULL,
    difficulty VARCHAR(16) NOT NULL,
    time_limit_ms INT NOT NULL,
    memory_limit_mb INT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_problem_slug (slug),
    KEY ix_problem_published_difficulty (published, difficulty),
    CONSTRAINT fk_problem_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT chk_problem_difficulty CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD')),
    CONSTRAINT chk_problem_time_limit CHECK (time_limit_ms > 0),
    CONSTRAINT chk_problem_memory_limit CHECK (memory_limit_mb > 0)
) ENGINE=InnoDB;

CREATE TABLE problem_tag (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(40) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_problem_tag_name (name)
) ENGINE=InnoDB;

CREATE TABLE problem_tag_link (
    problem_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (problem_id, tag_id),
    CONSTRAINT fk_problem_tag_link_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE,
    CONSTRAINT fk_problem_tag_link_tag FOREIGN KEY (tag_id) REFERENCES problem_tag (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE test_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    stdin MEDIUMTEXT NOT NULL,
    expected_output MEDIUMTEXT NOT NULL,
    sample BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uq_test_case_problem_ordinal (problem_id, ordinal),
    CONSTRAINT fk_test_case_problem FOREIGN KEY (problem_id) REFERENCES problem (id) ON DELETE CASCADE,
    CONSTRAINT chk_test_case_ordinal CHECK (ordinal > 0)
) ENGINE=InnoDB;

CREATE TABLE contest (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(100) NOT NULL,
    title VARCHAR(160) NOT NULL,
    starts_at TIMESTAMP NOT NULL,
    ends_at TIMESTAMP NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_contest_slug (slug),
    CONSTRAINT fk_contest_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT chk_contest_time_range CHECK (ends_at > starts_at)
) ENGINE=InnoDB;

CREATE TABLE contest_problem (
    contest_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    ordinal INT NOT NULL,
    PRIMARY KEY (contest_id, problem_id),
    UNIQUE KEY uq_contest_problem_ordinal (contest_id, ordinal),
    CONSTRAINT fk_contest_problem_contest FOREIGN KEY (contest_id) REFERENCES contest (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_problem_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT chk_contest_problem_ordinal CHECK (ordinal > 0)
) ENGINE=InnoDB;

CREATE TABLE contest_participant (
    contest_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    registered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (contest_id, user_id),
    CONSTRAINT fk_contest_participant_contest FOREIGN KEY (contest_id) REFERENCES contest (id) ON DELETE CASCADE,
    CONSTRAINT fk_contest_participant_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE submission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    contest_id BIGINT NULL,
    language VARCHAR(8) NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    verdict VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    queued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    execution_time_ms INT NULL,
    memory_used_kb BIGINT NULL,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    KEY ix_submission_queue (verdict, queued_at, id),
    KEY ix_submission_user_created (user_id, queued_at),
    KEY ix_submission_problem_verdict (problem_id, verdict),
    KEY ix_submission_contest_user (contest_id, user_id, verdict),
    CONSTRAINT fk_submission_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_submission_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT fk_submission_contest FOREIGN KEY (contest_id) REFERENCES contest (id),
    CONSTRAINT chk_submission_language CHECK (language IN ('JAVA', 'C')),
    CONSTRAINT chk_submission_verdict CHECK (
        verdict IN (
            'QUEUED', 'RUNNING', 'ACCEPTED', 'WRONG_ANSWER',
            'TIME_LIMIT_EXCEEDED', 'MEMORY_LIMIT_EXCEEDED',
            'OUTPUT_LIMIT_EXCEEDED', 'COMPILATION_ERROR',
            'RUNTIME_ERROR', 'INTERNAL_ERROR'
        )
    )
) ENGINE=InnoDB;

CREATE TABLE submission_test_result (
    submission_id BIGINT NOT NULL,
    test_case_id BIGINT NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    execution_time_ms INT NULL,
    memory_used_kb BIGINT NULL,
    output_snippet TEXT NULL,
    PRIMARY KEY (submission_id, test_case_id),
    CONSTRAINT fk_submission_test_result_submission FOREIGN KEY (submission_id) REFERENCES submission (id) ON DELETE CASCADE,
    CONSTRAINT fk_submission_test_result_test_case FOREIGN KEY (test_case_id) REFERENCES test_case (id),
    CONSTRAINT chk_submission_test_result_verdict CHECK (
        verdict IN (
            'ACCEPTED', 'WRONG_ANSWER', 'TIME_LIMIT_EXCEEDED',
            'MEMORY_LIMIT_EXCEEDED', 'OUTPUT_LIMIT_EXCEEDED',
            'COMPILATION_ERROR', 'RUNTIME_ERROR', 'INTERNAL_ERROR'
        )
    )
) ENGINE=InnoDB;

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    event_type VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id BIGINT NULL,
    details JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_audit_log_created (created_at),
    KEY ix_audit_log_actor_created (actor_user_id, created_at),
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id) ON DELETE SET NULL
) ENGINE=InnoDB;
