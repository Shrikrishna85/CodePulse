package com.codepulse.analytics;

import java.util.List;

import com.codepulse.auth.UserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AnalyticsOverview getOverview(UserPrincipal user) {
        List<VerdictCount> verdicts = jdbcTemplate.query(
                "SELECT verdict, COUNT(*) AS total FROM submission GROUP BY verdict ORDER BY verdict",
                (resultSet, rowNumber) ->
                        new VerdictCount(resultSet.getString("verdict"), resultSet.getLong("total")));
        List<DailyCount> dailySubmissions = jdbcTemplate.query(
                """
                SELECT DATE(queued_at) AS submission_day, COUNT(*) AS total
                FROM submission
                WHERE queued_at >= CURRENT_DATE - INTERVAL 6 DAY
                GROUP BY DATE(queued_at)
                ORDER BY submission_day
                """,
                (resultSet, rowNumber) ->
                        new DailyCount(resultSet.getDate("submission_day").toString(), resultSet.getLong("total")));
        List<ProblemAcceptance> acceptance = jdbcTemplate.query(
                """
                SELECT p.id, p.title, COUNT(s.id) AS total,
                       COALESCE(SUM(s.verdict = 'ACCEPTED'), 0) AS accepted
                FROM problem p
                LEFT JOIN submission s ON s.problem_id = p.id
                WHERE p.published = TRUE
                GROUP BY p.id
                ORDER BY p.id
                """,
                (resultSet, rowNumber) -> {
                    long total = resultSet.getLong("total");
                    long accepted = resultSet.getLong("accepted");
                    double rate = total == 0 ? 0.0 : (100.0 * accepted) / total;
                    return new ProblemAcceptance(
                            resultSet.getLong("id"),
                            resultSet.getString("title"),
                            accepted,
                            total,
                            rate);
                });
        PersonalStats personal = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(verdict = 'ACCEPTED'), 0) AS accepted,
                       COUNT(DISTINCT CASE WHEN verdict = 'ACCEPTED' THEN problem_id END) AS solved
                FROM submission
                WHERE user_id = ?
                """,
                (resultSet, rowNumber) -> new PersonalStats(
                        resultSet.getLong("total"),
                        resultSet.getLong("accepted"),
                        resultSet.getLong("solved")),
                user.id());
        return new AnalyticsOverview(verdicts, dailySubmissions, acceptance, personal);
    }

    public record AnalyticsOverview(
            List<VerdictCount> verdicts,
            List<DailyCount> submissionsPerDay,
            List<ProblemAcceptance> problemAcceptance,
            PersonalStats personalStats) {}

    public record VerdictCount(String verdict, long count) {}

    public record DailyCount(String date, long count) {}

    public record ProblemAcceptance(long problemId, String title, long accepted, long total, double ratePercent) {}

    public record PersonalStats(long submissions, long accepted, long solvedProblems) {}
}
