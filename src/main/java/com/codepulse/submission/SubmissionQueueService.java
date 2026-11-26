package com.codepulse.submission;

import java.util.List;
import java.util.Optional;

import com.codepulse.execution.Language;
import com.codepulse.execution.SandboxRunner;
import com.codepulse.execution.Verdict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SubmissionQueueService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public SubmissionQueueService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<ClaimedSubmission> claimNext() {
        return Optional.ofNullable(transactionTemplate.execute(status -> {
            List<ClaimedSubmission> jobs = jdbcTemplate.query(
                    """
                    SELECT s.id, s.user_id, s.problem_id, s.language, s.source_code,
                           p.time_limit_ms, p.memory_limit_mb
                    FROM submission s
                    JOIN problem p ON p.id = s.problem_id
                    WHERE s.verdict = 'QUEUED'
                    ORDER BY s.queued_at, s.id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """,
                    (resultSet, rowNumber) -> new ClaimedSubmission(
                            resultSet.getLong("id"),
                            resultSet.getLong("user_id"),
                            resultSet.getLong("problem_id"),
                            Language.valueOf(resultSet.getString("language")),
                            resultSet.getString("source_code"),
                            resultSet.getInt("time_limit_ms"),
                            resultSet.getInt("memory_limit_mb")));
            if (jobs.isEmpty()) {
                return null;
            }

            ClaimedSubmission job = jobs.get(0);
            int updated = jdbcTemplate.update(
                    """
                    UPDATE submission
                    SET verdict = 'RUNNING', started_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND verdict = 'QUEUED'
                    """,
                    job.id());
            if (updated != 1) {
                throw new IllegalStateException("Locked submission was not available for claiming");
            }
            return job;
        }));
    }

    public List<SandboxRunner.TestCaseInput> getTestCases(long problemId) {
        return jdbcTemplate.query(
                """
                SELECT id, ordinal, stdin, expected_output, sample
                FROM test_case
                WHERE problem_id = ?
                ORDER BY ordinal
                """,
                (resultSet, rowNumber) -> new SandboxRunner.TestCaseInput(
                        resultSet.getLong("id"),
                        resultSet.getInt("ordinal"),
                        resultSet.getString("stdin"),
                        resultSet.getString("expected_output"),
                        resultSet.getBoolean("sample")),
                problemId);
    }

    public void complete(ClaimedSubmission job, SandboxRunner.ExecutionSummary result) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM submission_test_result WHERE submission_id = ?", job.id());
            for (SandboxRunner.CaseResult testResult : result.testResults()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO submission_test_result
                            (submission_id, test_case_id, verdict, execution_time_ms, output_snippet)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        job.id(),
                        testResult.testCaseId(),
                        testResult.verdict().name(),
                        testResult.executionTimeMs(),
                        testResult.sample() ? testResult.feedbackSnippet() : null);
            }
            int totalRuntime = result.testResults().stream()
                    .mapToInt(testResult -> Math.toIntExact(testResult.executionTimeMs()))
                    .sum();
            int updated = jdbcTemplate.update(
                    """
                    UPDATE submission
                    SET verdict = ?, completed_at = CURRENT_TIMESTAMP, execution_time_ms = ?, error_message = ?
                    WHERE id = ? AND verdict = 'RUNNING'
                    """,
                    result.verdict().name(),
                    totalRuntime,
                    result.errorSnippet(),
                    job.id());
            if (updated != 1) {
                throw new IllegalStateException("Submission was no longer marked RUNNING at completion");
            }
        });
    }

    public void failInternally(long submissionId, String message) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update(
                """
                UPDATE submission
                SET verdict = ?, completed_at = CURRENT_TIMESTAMP, error_message = ?
                WHERE id = ? AND verdict = 'RUNNING'
                """,
                Verdict.INTERNAL_ERROR.name(),
                safeError(message),
                submissionId));
    }

    public void recoverRunningSubmissions() {
        jdbcTemplate.update(
                """
                UPDATE submission
                SET verdict = 'QUEUED', started_at = NULL
                WHERE verdict = 'RUNNING'
                """);
    }

    private static String safeError(String message) {
        if (message == null || message.isBlank()) {
            return "The worker could not complete this submission.";
        }
        return message.substring(0, Math.min(message.length(), 2_000));
    }

    public record ClaimedSubmission(
            long id,
            long userId,
            long problemId,
            Language language,
            String sourceCode,
            int timeLimitMs,
            int memoryLimitMb) {}
}
