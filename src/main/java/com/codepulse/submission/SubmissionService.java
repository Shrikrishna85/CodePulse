package com.codepulse.submission;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

import com.codepulse.audit.AuditLogService;
import com.codepulse.auth.UserPrincipal;
import com.codepulse.execution.Language;
import com.codepulse.execution.Verdict;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SubmissionService {

    private static final int MAX_SOURCE_BYTES = 64 * 1024;
    private static final int MAX_SUBMISSION_LIST_SIZE = 100;

    private static final RowMapper<SubmissionSummary> SUMMARY_MAPPER = (resultSet, rowNumber) ->
            new SubmissionSummary(
                    resultSet.getLong("id"),
                    resultSet.getLong("problem_id"),
                    resultSet.getString("problem_title"),
                    resultSet.getString("language"),
                    Verdict.valueOf(resultSet.getString("verdict")),
                    resultSet.getTimestamp("queued_at").toInstant().toString());

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public SubmissionService(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public long submit(CreateSubmission request, UserPrincipal user) {
        if (request == null || request.sourceCode() == null || request.sourceCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source code is required");
        }
        if (request.sourceCode().getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Source code is limited to 64 KiB");
        }
        Language language;
        try {
            language = Language.valueOf(request.language() == null ? "" : request.language());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Language must be JAVA or C");
        }

        Integer published = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM problem WHERE id = ? AND published = TRUE",
                Integer.class,
                request.problemId());
        if (published == null || published == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Published problem not found");
        }

        Long contestId = request.contestId();
        if (contestId != null) {
            Integer eligible = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM contest c
                    JOIN contest_participant cp ON cp.contest_id = c.id AND cp.user_id = ?
                    JOIN contest_problem cpr ON cpr.contest_id = c.id AND cpr.problem_id = ?
                    WHERE c.id = ? AND CURRENT_TIMESTAMP BETWEEN c.starts_at AND c.ends_at
                    """,
                    Integer.class,
                    user.id(),
                    request.problemId(),
                    contestId);
            if (eligible == null || eligible == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Register for an active contest containing this problem before submitting");
            }
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO submission (user_id, problem_id, contest_id, language, source_code)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, user.id());
            statement.setLong(2, request.problemId());
            if (contestId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, contestId);
            }
            statement.setString(4, language.name());
            statement.setString(5, request.sourceCode());
            return statement;
        };
        jdbcTemplate.update(statementCreator, keyHolder);
        long submissionId = Objects.requireNonNull(keyHolder.getKey(), "Database did not return a submission id")
                .longValue();
        auditLogService.record(user.id(), "SUBMISSION_QUEUED", "SUBMISSION", submissionId);
        return submissionId;
    }

    public List<SubmissionSummary> listMine(long userId) {
        return jdbcTemplate.query(
                """
                SELECT s.id, s.problem_id, p.title AS problem_title, s.language, s.verdict, s.queued_at
                FROM submission s
                JOIN problem p ON p.id = s.problem_id
                WHERE s.user_id = ?
                ORDER BY s.queued_at DESC, s.id DESC
                LIMIT ?
                """,
                SUMMARY_MAPPER,
                userId,
                MAX_SUBMISSION_LIST_SIZE);
    }

    public SubmissionDetail getMine(long submissionId, long userId) {
        List<SubmissionDetail> submissions = jdbcTemplate.query(
                """
                SELECT s.id, s.problem_id, p.title AS problem_title, s.language, s.source_code,
                       s.verdict, s.queued_at, s.started_at, s.completed_at, s.execution_time_ms,
                       s.error_message
                FROM submission s
                JOIN problem p ON p.id = s.problem_id
                WHERE s.id = ? AND s.user_id = ?
                """,
                (resultSet, rowNumber) -> new SubmissionDetail(
                        resultSet.getLong("id"),
                        resultSet.getLong("problem_id"),
                        resultSet.getString("problem_title"),
                        resultSet.getString("language"),
                        resultSet.getString("source_code"),
                        Verdict.valueOf(resultSet.getString("verdict")),
                        resultSet.getTimestamp("queued_at").toInstant().toString(),
                        resultSet.getTimestamp("started_at") == null
                                ? null
                                : resultSet.getTimestamp("started_at").toInstant().toString(),
                        resultSet.getTimestamp("completed_at") == null
                                ? null
                                : resultSet.getTimestamp("completed_at").toInstant().toString(),
                        (Integer) resultSet.getObject("execution_time_ms"),
                        resultSet.getString("error_message"),
                        List.of()),
                submissionId,
                userId);
        if (submissions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found");
        }

        SubmissionDetail submission = submissions.get(0);
        List<TestResultView> results = jdbcTemplate.query(
                """
                SELECT r.verdict, r.execution_time_ms, r.output_snippet, t.ordinal, t.sample
                FROM submission_test_result r
                JOIN test_case t ON t.id = r.test_case_id
                WHERE r.submission_id = ?
                ORDER BY t.ordinal
                """,
                (resultSet, rowNumber) -> {
                    boolean sample = resultSet.getBoolean("sample");
                    return new TestResultView(
                            resultSet.getInt("ordinal"),
                            sample,
                            Verdict.valueOf(resultSet.getString("verdict")),
                            (Integer) resultSet.getObject("execution_time_ms"),
                            sample ? resultSet.getString("output_snippet") : null);
                },
                submissionId);
        return new SubmissionDetail(
                submission.id(),
                submission.problemId(),
                submission.problemTitle(),
                submission.language(),
                submission.sourceCode(),
                submission.verdict(),
                submission.queuedAt(),
                submission.startedAt(),
                submission.completedAt(),
                submission.executionTimeMs(),
                submission.errorMessage(),
                results);
    }

    public record CreateSubmission(long problemId, String language, String sourceCode, Long contestId) {}

    public record SubmissionSummary(
            long id,
            long problemId,
            String problemTitle,
            String language,
            Verdict verdict,
            String queuedAt) {}

    public record SubmissionDetail(
            long id,
            long problemId,
            String problemTitle,
            String language,
            String sourceCode,
            Verdict verdict,
            String queuedAt,
            String startedAt,
            String completedAt,
            Integer executionTimeMs,
            String errorMessage,
            List<TestResultView> testResults) {}

    public record TestResultView(
            int ordinal,
            boolean sample,
            Verdict verdict,
            Integer executionTimeMs,
            String feedbackSnippet) {}
}
