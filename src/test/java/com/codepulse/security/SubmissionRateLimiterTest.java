package com.codepulse.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
