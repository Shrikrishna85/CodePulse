package com.codepulse.contest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContestScoring {

    private ContestScoring() {}

    public static List<ScoreboardEntry> calculate(
            Instant contestStart,
            List<Participant> participants,
            List<Attempt> attempts) {
        Map<Long, Score> scores = new LinkedHashMap<>();
        for (Participant participant : participants) {
            scores.put(participant.userId(), new Score(participant.username()));
        }

        Map<UserProblem, ProblemScore> problemScores = new LinkedHashMap<>();
        for (Attempt attempt : attempts) {
            Score score = scores.get(attempt.userId());
            if (score == null) {
                continue;
            }
            UserProblem key = new UserProblem(attempt.userId(), attempt.problemId());
            ProblemScore problemScore = problemScores.computeIfAbsent(key, ignored -> new ProblemScore());
            if (problemScore.acceptedAt != null) {
                continue;
            }
            if ("ACCEPTED".equals(attempt.verdict())) {
                problemScore.acceptedAt = attempt.submittedAt();
                score.solved++;
                long solvedMinutes = Math.max(0, Duration.between(contestStart, attempt.submittedAt()).toMinutes());
                score.penaltyMinutes += solvedMinutes + problemScore.wrongAttempts * 20L;
            } else if (!"QUEUED".equals(attempt.verdict()) && !"RUNNING".equals(attempt.verdict())) {
                problemScore.wrongAttempts++;
            }
        }

        List<ScoreboardEntry> entries = new ArrayList<>();
        scores.forEach((userId, score) ->
                entries.add(new ScoreboardEntry(0, score.username, score.solved, score.penaltyMinutes)));
        entries.sort(Comparator
                .comparingInt(ScoreboardEntry::solvedCount)
                .reversed()
                .thenComparingLong(ScoreboardEntry::penaltyMinutes)
                .thenComparing(ScoreboardEntry::username, String.CASE_INSENSITIVE_ORDER));

        List<ScoreboardEntry> ranked = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            ScoreboardEntry entry = entries.get(index);
            ranked.add(new ScoreboardEntry(index + 1, entry.username(), entry.solvedCount(), entry.penaltyMinutes()));
        }
        return ranked;
    }

    public record Participant(long userId, String username) {}

    public record Attempt(long userId, long problemId, String verdict, Instant submittedAt) {}

    public record ScoreboardEntry(int rank, String username, int solvedCount, long penaltyMinutes) {}

    private record UserProblem(long userId, long problemId) {}

    private static final class ProblemScore {
        private int wrongAttempts;
        private Instant acceptedAt;
    }

    private static final class Score {
        private final String username;
        private int solved;
        private long penaltyMinutes;

        private Score(String username) {
            this.username = username;
        }
    }
}
