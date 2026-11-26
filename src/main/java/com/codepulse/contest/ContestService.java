package com.codepulse.contest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ContestService {

    private final JdbcTemplate jdbcTemplate;

    public ContestService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ContestSummary> listContests() {
        return jdbcTemplate.query(
                """
                SELECT id, slug, title, starts_at, ends_at
                FROM contest
                ORDER BY starts_at DESC
                """,
                (resultSet, rowNumber) -> new ContestSummary(
                        resultSet.getLong("id"),
                        resultSet.getString("slug"),
                        resultSet.getString("title"),
                        resultSet.getTimestamp("starts_at").toInstant().toString(),
                        resultSet.getTimestamp("ends_at").toInstant().toString()));
    }

    @Transactional
    public void register(long contestId, long userId) {
        Integer accepting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM contest WHERE id = ? AND ends_at > CURRENT_TIMESTAMP",
                Integer.class,
                contestId);
        if (accepting == null || accepting == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contest is not open for registration");
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO contest_participant (contest_id, user_id) VALUES (?, ?)",
                    contestId,
                    userId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already registered for this contest");
        }
    }

    public List<ContestProblem> listProblems(long contestId) {
        requireContest(contestId);
        return jdbcTemplate.query(
                """
                SELECT p.id, p.slug, p.title, p.difficulty, cp.ordinal
                FROM contest_problem cp
                JOIN problem p ON p.id = cp.problem_id
                WHERE cp.contest_id = ? AND p.published = TRUE
                ORDER BY cp.ordinal
                """,
                (resultSet, rowNumber) -> new ContestProblem(
                        resultSet.getLong("id"),
                        resultSet.getString("slug"),
                        resultSet.getString("title"),
                        resultSet.getString("difficulty"),
                        resultSet.getInt("ordinal")),
                contestId);
    }

    public List<ContestScoring.ScoreboardEntry> getScoreboard(long contestId) {
        ContestWindow window = requireContest(contestId);
        List<ContestScoring.Participant> participants = jdbcTemplate.query(
                """
                SELECT u.id, u.username
                FROM contest_participant cp
                JOIN app_user u ON u.id = cp.user_id
                WHERE cp.contest_id = ?
                ORDER BY u.username
                """,
                (resultSet, rowNumber) -> new ContestScoring.Participant(
                        resultSet.getLong("id"),
                        resultSet.getString("username")),
                contestId);
        List<ContestScoring.Attempt> attempts = jdbcTemplate.query(
                """
                SELECT user_id, problem_id, verdict, queued_at
                FROM submission
                WHERE contest_id = ? AND queued_at BETWEEN ? AND ?
                ORDER BY queued_at, id
                """,
                (resultSet, rowNumber) -> new ContestScoring.Attempt(
                        resultSet.getLong("user_id"),
                        resultSet.getLong("problem_id"),
                        resultSet.getString("verdict"),
                        resultSet.getTimestamp("queued_at").toInstant()),
                contestId,
                Timestamp.from(window.startsAt()),
                Timestamp.from(window.endsAt()));

        return ContestScoring.calculate(window.startsAt(), participants, attempts);
    }

    private ContestWindow requireContest(long contestId) {
        List<ContestWindow> rows = jdbcTemplate.query(
                "SELECT starts_at, ends_at FROM contest WHERE id = ?",
                (resultSet, rowNumber) -> new ContestWindow(
                        resultSet.getTimestamp("starts_at").toInstant(),
                        resultSet.getTimestamp("ends_at").toInstant()),
                contestId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contest not found");
        }
        return rows.get(0);
    }

    private record ContestWindow(Instant startsAt, Instant endsAt) {}

    public record ContestSummary(long id, String slug, String title, String startsAt, String endsAt) {}

    public record ContestProblem(long id, String slug, String title, String difficulty, int ordinal) {}

}
