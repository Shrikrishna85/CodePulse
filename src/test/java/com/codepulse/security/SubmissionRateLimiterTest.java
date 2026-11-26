package com.codepulse.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class SubmissionRateLimiterTest {

    @Test
    void allowsBurstThenRejectsUntilTokensRefill() {
        SubmissionRateLimiter limiter = new SubmissionRateLimiter();

        for (int request = 0; request < 5; request++) {
            assertTrue(limiter.tryAcquireAt(7, 1_000));
        }
        assertFalse(limiter.tryAcquireAt(7, 1_000));
        assertFalse(limiter.tryAcquireAt(7, 6_999));
        assertTrue(limiter.tryAcquireAt(7, 7_000));
    }

    @Test
    void keepsBucketsIndependentAcrossUsers() {
        SubmissionRateLimiter limiter = new SubmissionRateLimiter();

        for (int request = 0; request < 5; request++) {
            assertTrue(limiter.tryAcquireAt(7, 0));
        }
        assertFalse(limiter.tryAcquireAt(7, 0));
        assertTrue(limiter.tryAcquireAt(8, 0));
    }

    @Test
    void concurrentRequestsCannotSpendMoreThanTheBurstCapacity() throws Exception {
        SubmissionRateLimiter limiter = new SubmissionRateLimiter();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int request = 0; request < 100; request++) {
                results.add(executor.submit(() -> limiter.tryAcquireAt(9, 0)));
            }
            long acquired = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) acquired++;
            }
            assertEquals(5, acquired);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refillNeverExceedsTheBurstCapacity() {
        SubmissionRateLimiter limiter = new SubmissionRateLimiter();

        assertTrue(limiter.tryAcquireAt(10, 0));
        for (int request = 0; request < 5; request++) {
            assertTrue(limiter.tryAcquireAt(10, 600_000));
        }
        assertFalse(limiter.tryAcquireAt(10, 600_000));
    }
}
