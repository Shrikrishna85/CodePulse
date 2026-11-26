package com.codepulse.similarity;

import java.util.List;

import com.codepulse.audit.AuditLogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimilarityService {

    private static final double REVIEW_THRESHOLD = 0.80;
    private static final int MAX_COMPARISONS = 500;

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public SimilarityService(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void compareAcceptedSubmission(long submissionId) {
        List<SubmissionSource> currentRows = jdbcTemplate.query(
                """
                SELECT id, user_id, problem_id, source_code
                FROM submission
                WHERE id = ? AND verdict = 'ACCEPTED'
                """,
                (resultSet, rowNumber) -> new SubmissionSource(
                        resultSet.getLong("id"),
                        resultSet.getLong("user_id"),
                        resultSet.getLong("problem_id"),
                        resultSet.getString("source_code")),
                submissionId);
        if (currentRows.isEmpty()) {
            return;
        }
        SubmissionSource current = currentRows.get(0);
        List<SubmissionSource> peers = jdbcTemplate.query(
                """
                SELECT id, user_id, problem_id, source_code
                FROM submission
                WHERE problem_id = ? AND verdict = 'ACCEPTED' AND user_id <> ? AND id <> ?
                ORDER BY completed_at DESC, id DESC
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new SubmissionSource(
                        resultSet.getLong("id"),
                        resultSet.getLong("user_id"),
                        resultSet.getLong("problem_id"),
                        resultSet.getString("source_code")),
                current.problemId(),
                current.userId(),
                current.id(),
                MAX_COMPARISONS);

        for (SubmissionSource peer : peers) {
            double score = SimilarityScorer.jaccardSimilarity(current.sourceCode(), peer.sourceCode());
            if (score >= REVIEW_THRESHOLD) {
                insertReviewPair(current, peer, score);
            }
        }
    }

    public List<SimilarityPair> listReviewPairs() {
        return jdbcTemplate.query(
                """
                SELECT pair.id, pair.problem_id, pair.submission_a_id, pair.submission_b_id,
                       user_a.username AS username_a, user_b.username AS username_b,
                       pair.similarity_score, pair.review_status, pair.created_at
                FROM similarity_review_pair pair
                JOIN app_user user_a ON user_a.id = pair.user_a_id
                JOIN app_user user_b ON user_b.id = pair.user_b_id
                ORDER BY pair.similarity_score DESC, pair.created_at DESC
                """,
                (resultSet, rowNumber) -> new SimilarityPair(
                        resultSet.getLong("id"),
                        resultSet.getLong("problem_id"),
                        resultSet.getLong("submission_a_id"),
                        resultSet.getLong("submission_b_id"),
                        resultSet.getString("username_a"),
                        resultSet.getString("username_b"),
                        resultSet.getDouble("similarity_score"),
                        resultSet.getString("review_status"),
                        resultSet.getTimestamp("created_at").toInstant().toString()));
    }

    public void markReviewed(long pairId, long reviewerUserId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE similarity_review_pair
                SET review_status = 'REVIEWED', reviewed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND review_status = 'PENDING'
                """,
                pairId);
        if (updated == 0) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM similarity_review_pair WHERE id = ?",
                    Integer.class,
                    pairId);
            if (exists == null || exists == 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Similarity review pair not found");
            }
        } else {
            auditLogService.record(reviewerUserId, "SIMILARITY_PAIR_REVIEWED", "SIMILARITY_PAIR", pairId);
        }
    }

    private void insertReviewPair(SubmissionSource current, SubmissionSource peer, double score) {
        SubmissionSource first = current.id() < peer.id() ? current : peer;
        SubmissionSource second = current.id() < peer.id() ? peer : current;
        jdbcTemplate.update(
                """
                INSERT INTO similarity_review_pair
                    (problem_id, submission_a_id, submission_b_id, user_a_id, user_b_id, similarity_score)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE similarity_score = GREATEST(similarity_score, VALUES(similarity_score))
                """,
                current.problemId(),
                first.id(),
                second.id(),
                first.userId(),
                second.userId(),
                score);
    }

    private record SubmissionSource(long id, long userId, long problemId, String sourceCode) {}

    public record SimilarityPair(
            long id,
            long problemId,
            long submissionAId,
            long submissionBId,
            String usernameA,
            String usernameB,
            double score,
            String reviewStatus,
            String createdAt) {}
}
