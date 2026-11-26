USE codepulse;

CREATE TABLE similarity_review_pair (
    id BIGINT NOT NULL AUTO_INCREMENT,
    problem_id BIGINT NOT NULL,
    submission_a_id BIGINT NOT NULL,
    submission_b_id BIGINT NOT NULL,
    user_a_id BIGINT NOT NULL,
    user_b_id BIGINT NOT NULL,
    similarity_score DECIMAL(6, 5) NOT NULL,
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_similarity_pair_submissions (submission_a_id, submission_b_id),
    KEY ix_similarity_pair_review (review_status, similarity_score),
    CONSTRAINT fk_similarity_pair_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT fk_similarity_pair_submission_a FOREIGN KEY (submission_a_id) REFERENCES submission (id) ON DELETE CASCADE,
    CONSTRAINT fk_similarity_pair_submission_b FOREIGN KEY (submission_b_id) REFERENCES submission (id) ON DELETE CASCADE,
    CONSTRAINT fk_similarity_pair_user_a FOREIGN KEY (user_a_id) REFERENCES app_user (id),
    CONSTRAINT fk_similarity_pair_user_b FOREIGN KEY (user_b_id) REFERENCES app_user (id),
    CONSTRAINT chk_similarity_pair_score CHECK (similarity_score >= 0 AND similarity_score <= 1),
    CONSTRAINT chk_similarity_pair_status CHECK (review_status IN ('PENDING', 'REVIEWED')),
    CONSTRAINT chk_similarity_pair_users CHECK (user_a_id <> user_b_id)
) ENGINE=InnoDB;
