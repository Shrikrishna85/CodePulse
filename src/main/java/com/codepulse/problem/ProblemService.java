package com.codepulse.problem;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.codepulse.audit.AuditLogService;
import org.springframework.dao.DuplicateKeyException;
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
public class ProblemService {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TEST_CASES = 100;
    private static final int MAX_CASE_TEXT_CHARS = 500_000;

    private static final RowMapper<ProblemSummary> SUMMARY_MAPPER = (resultSet, rowNumber) ->
            new ProblemSummary(
                    resultSet.getLong("id"),
                    resultSet.getString("slug"),
                    resultSet.getString("title"),
                    resultSet.getString("difficulty"),
                    resultSet.getInt("time_limit_ms"),
                    resultSet.getInt("memory_limit_mb"),
                    splitTags(resultSet.getString("tags")));

    private static final RowMapper<ProblemDetail> DETAIL_MAPPER = (resultSet, rowNumber) ->
            new ProblemDetail(
                    resultSet.getLong("id"),
                    resultSet.getString("slug"),
                    resultSet.getString("title"),
                    resultSet.getString("statement"),
                    resultSet.getString("difficulty"),
                    resultSet.getInt("time_limit_ms"),
                    resultSet.getInt("memory_limit_mb"),
                    splitTags(resultSet.getString("tags")),
                    List.of());

    private final JdbcTemplate jdbcTemplate;
    private final AuditLogService auditLogService;

    public ProblemService(JdbcTemplate jdbcTemplate, AuditLogService auditLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditLogService = auditLogService;
    }

    public List<ProblemSummary> listPublishedProblems() {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.slug, p.title, p.difficulty, p.time_limit_ms, p.memory_limit_mb,
                       COALESCE(GROUP_CONCAT(DISTINCT t.name ORDER BY t.name SEPARATOR ','), '') AS tags
                FROM problem p
                LEFT JOIN problem_tag_link link ON link.problem_id = p.id
                LEFT JOIN problem_tag t ON t.id = link.tag_id
                WHERE p.published = TRUE
                GROUP BY p.id
                ORDER BY p.id
                """,
                SUMMARY_MAPPER);
    }

    public ProblemDetail getPublishedProblem(String slug) {
        List<ProblemDetail> problems = jdbcTemplate.query(
                """
                SELECT p.id, p.slug, p.title, p.statement, p.difficulty,
                       p.time_limit_ms, p.memory_limit_mb,
                       COALESCE(GROUP_CONCAT(DISTINCT t.name ORDER BY t.name SEPARATOR ','), '') AS tags
                FROM problem p
                LEFT JOIN problem_tag_link link ON link.problem_id = p.id
                LEFT JOIN problem_tag t ON t.id = link.tag_id
                WHERE p.published = TRUE AND p.slug = ?
                GROUP BY p.id
                """,
                DETAIL_MAPPER,
                slug);
        if (problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found");
        }

        ProblemDetail problem = problems.get(0);
        List<SampleCase> samples = jdbcTemplate.query(
                """
                SELECT ordinal, stdin, expected_output
                FROM test_case
                WHERE problem_id = ? AND sample = TRUE
                ORDER BY ordinal
                """,
                (resultSet, rowNumber) -> new SampleCase(
                        resultSet.getInt("ordinal"),
                        resultSet.getString("stdin"),
                        resultSet.getString("expected_output")),
                problem.id());
        return new ProblemDetail(
                problem.id(),
                problem.slug(),
                problem.title(),
                problem.statement(),
                problem.difficulty(),
                problem.timeLimitMs(),
                problem.memoryLimitMb(),
                problem.tags(),
                samples);
    }

    @Transactional
    public long createProblem(CreateProblem input, long creatorUserId) {
        validateProblem(input);
        String slug = input.slug().trim().toLowerCase(Locale.ROOT);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        PreparedStatementCreator statementCreator = connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO problem
                        (slug, title, statement, difficulty, time_limit_ms, memory_limit_mb, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, slug);
            statement.setString(2, input.title().trim());
            statement.setString(3, input.statement());
            statement.setString(4, input.difficulty().toUpperCase(Locale.ROOT));
            statement.setInt(5, input.timeLimitMs());
            statement.setInt(6, input.memoryLimitMb());
            statement.setLong(7, creatorUserId);
            return statement;
        };
        try {
            jdbcTemplate.update(statementCreator, keyHolder);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Problem slug is already in use");
        }
        long problemId = Objects.requireNonNull(keyHolder.getKey(), "Database did not return a problem id")
                .longValue();

        for (String tag : normalizeTags(input.tags())) {
            jdbcTemplate.update(
                    "INSERT INTO problem_tag (name) VALUES (?) ON DUPLICATE KEY UPDATE name = VALUES(name)",
                    tag);
            Long tagId = jdbcTemplate.queryForObject(
                    "SELECT id FROM problem_tag WHERE name = ?",
                    Long.class,
                    tag);
            jdbcTemplate.update(
                    "INSERT INTO problem_tag_link (problem_id, tag_id) VALUES (?, ?)",
                    problemId,
                    Objects.requireNonNull(tagId));
        }

        auditLogService.record(creatorUserId, "PROBLEM_CREATED", "PROBLEM", problemId);
        return problemId;
    }

    @Transactional
    public int addTestCase(long problemId, NewTestCase input, long actorUserId) {
        validateTestCase(input);
        lockUnpublishedProblem(problemId);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM test_case WHERE problem_id = ?",
                Integer.class,
                problemId);
        if (count != null && count >= MAX_TEST_CASES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A problem cannot have more than 100 test cases");
        }

        Integer nextOrdinal = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM test_case WHERE problem_id = ?",
                Integer.class,
                problemId);
        int ordinal = Objects.requireNonNull(nextOrdinal);
        jdbcTemplate.update(
                """
                INSERT INTO test_case (problem_id, ordinal, stdin, expected_output, sample)
                VALUES (?, ?, ?, ?, ?)
                """,
                problemId,
                ordinal,
                input.stdin(),
                input.expectedOutput(),
                input.sample());
        auditLogService.record(actorUserId, "TEST_CASE_CREATED", "PROBLEM", problemId);
        return ordinal;
    }

    @Transactional
    public void setPublished(long problemId, boolean published, long actorUserId) {
        List<Long> existingProblems = jdbcTemplate.query(
                "SELECT id FROM problem WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                problemId);
        if (existingProblems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found");
        }

        if (published) {
            List<TestCaseCounts> counts = jdbcTemplate.query(
                    """
                    SELECT COALESCE(SUM(sample = TRUE), 0) AS samples,
                           COALESCE(SUM(sample = FALSE), 0) AS hidden
                    FROM test_case
                    WHERE problem_id = ?
                    """,
                    (resultSet, rowNumber) -> new TestCaseCounts(
                            resultSet.getInt("samples"),
                            resultSet.getInt("hidden")),
                    problemId);
            TestCaseCounts problemCounts = counts.get(0);
            if (problemCounts.samples() == 0 || problemCounts.hidden() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "A published problem needs at least one sample and one hidden test case");
            }
        }

        int updated = jdbcTemplate.update(
                "UPDATE problem SET published = ? WHERE id = ?",
                published,
                problemId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found");
        }
        auditLogService.record(
                actorUserId,
                published ? "PROBLEM_PUBLISHED" : "PROBLEM_UNPUBLISHED",
                "PROBLEM",
                problemId);
    }

    public List<AdminTestCase> listTestCases(long problemId) {
        requireProblemExists(problemId);
        return jdbcTemplate.query(
                """
                SELECT ordinal, stdin, expected_output, sample
                FROM test_case
                WHERE problem_id = ?
                ORDER BY ordinal
                """,
                (resultSet, rowNumber) -> new AdminTestCase(
                        resultSet.getInt("ordinal"),
                        resultSet.getString("stdin"),
                        resultSet.getString("expected_output"),
                        resultSet.getBoolean("sample")),
                problemId);
    }

    private void lockUnpublishedProblem(long problemId) {
        List<Boolean> published = jdbcTemplate.query(
                "SELECT published FROM problem WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getBoolean("published"),
                problemId);
        if (published.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found");
        }
        if (published.get(0)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Unpublish the problem before modifying its test cases");
        }
    }

    private void requireProblemExists(long problemId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM problem WHERE id = ?",
                Integer.class,
                problemId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Problem not found");
        }
    }

    private static void validateProblem(CreateProblem input) {
        if (input == null
                || input.slug() == null
                || !input.slug().trim().matches("[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*")
                || input.slug().trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem slug must be URL-safe");
        }
        if (input.title() == null || input.title().isBlank() || input.title().trim().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem title is required and limited to 160 characters");
        }
        if (input.statement() == null || input.statement().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Problem statement is required");
        }
        if (input.difficulty() == null
                || !List.of("EASY", "MEDIUM", "HARD").contains(input.difficulty().toUpperCase(Locale.ROOT))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Difficulty must be EASY, MEDIUM, or HARD");
        }
        if (input.timeLimitMs() < 250 || input.timeLimitMs() > 30_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Time limit must be between 250 and 30000 ms");
        }
        if (input.memoryLimitMb() < 32 || input.memoryLimitMb() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Memory limit must be between 32 and 1024 MB");
        }
        normalizeTags(input.tags());
    }

    private static void validateTestCase(NewTestCase input) {
        if (input == null || input.stdin() == null || input.expectedOutput() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test input and expected output are required");
        }
        if (input.stdin().length() > MAX_CASE_TEXT_CHARS || input.expectedOutput().length() > MAX_CASE_TEXT_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test input and output are limited to 500000 characters");
        }
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || !tag.trim().matches("[a-zA-Z0-9+#-]{1,40}")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Tags must be 1-40 characters using letters, numbers, '+', '#', or '-'");
            }
            normalized.add(tag.trim().toLowerCase(Locale.ROOT));
        }
        if (normalized.size() > MAX_TAGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A problem can have at most 10 tags");
        }
        return new ArrayList<>(normalized);
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return List.of(tags.split(","));
    }

    private record TestCaseCounts(int samples, int hidden) {}

    public record CreateProblem(
            String slug,
            String title,
            String statement,
            String difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            List<String> tags) {}

    public record NewTestCase(String stdin, String expectedOutput, boolean sample) {}

    public record ProblemSummary(
            long id,
            String slug,
            String title,
            String difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            List<String> tags) {}

    public record ProblemDetail(
            long id,
            String slug,
            String title,
            String statement,
            String difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            List<String> tags,
            List<SampleCase> samples) {}

    public record SampleCase(int ordinal, String stdin, String expectedOutput) {}

    public record AdminTestCase(int ordinal, String stdin, String expectedOutput, boolean sample) {}
}
