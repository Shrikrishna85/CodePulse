package com.codepulse.contest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class ContestScoringTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void addsTwentyMinutePenaltyForEachWrongAttemptBeforeAcceptance() {
        List<ContestScoring.ScoreboardEntry> scoreboard = ContestScoring.calculate(
                START,
                List.of(new ContestScoring.Participant(1, "student")),
                List.of(
                        attempt(1, "WRONG_ANSWER", 10),
                        attempt(1, "COMPILATION_ERROR", 15),
                        attempt(1, "ACCEPTED", 25),
                        attempt(1, "WRONG_ANSWER", 30)));

        assertEquals(1, scoreboard.get(0).solvedCount());
        assertEquals(65, scoreboard.get(0).penaltyMinutes());
    }

    @Test
    void ranksSolvedCountBeforePenaltyAndExcludesPendingSubmissions() {
        List<ContestScoring.ScoreboardEntry> scoreboard = ContestScoring.calculate(
                START,
                List.of(
                        new ContestScoring.Participant(1, "first"),
                        new ContestScoring.Participant(2, "second")),
                List.of(
                        attempt(1, "ACCEPTED", 40),
                        attempt(2, "ACCEPTED", 5),
                        attempt(2, "QUEUED", 1),
                        new ContestScoring.Attempt(2, 2, "ACCEPTED", START.plusSeconds(900))));

        assertEquals("second", scoreboard.get(0).username());
        assertEquals(2, scoreboard.get(0).solvedCount());
        assertEquals(20, scoreboard.get(0).penaltyMinutes());
    }

    private static ContestScoring.Attempt attempt(long userId, String verdict, long minutesAfterStart) {
        return new ContestScoring.Attempt(userId, 1, verdict, START.plusSeconds(minutesAfterStart * 60));
    }
}
