package com.codepulse.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

@Component
public class SubmissionRateLimiter {

    private static final int CAPACITY = 5;
    private static final long REFILL_INTERVAL_MILLIS = 6_000;
    private static final int MAX_TRACKED_USERS = 50_000;

    private final ConcurrentMap<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public boolean tryAcquire(long userId) {
        return tryAcquireAt(userId, System.nanoTime() / 1_000_000);
    }

    boolean tryAcquireAt(long userId, long nowMillis) {
        Bucket bucket = buckets.get(userId);
        if (bucket == null) {
            if (buckets.size() >= MAX_TRACKED_USERS) {
                return false;
            }
            bucket = buckets.computeIfAbsent(userId, ignored -> new Bucket(CAPACITY, nowMillis));
        }
        return bucket.tryAcquire(nowMillis);
    }

    private static final class Bucket {

        private final int capacity;
        private int tokens;
        private long lastRefillMillis;

        private Bucket(int capacity, long nowMillis) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillMillis = nowMillis;
        }

        private synchronized boolean tryAcquire(long nowMillis) {
            if (nowMillis > lastRefillMillis) {
                long intervals = (nowMillis - lastRefillMillis) / REFILL_INTERVAL_MILLIS;
                if (intervals > 0) {
                    tokens = (int) Math.min(capacity, tokens + intervals);
                    lastRefillMillis += intervals * REFILL_INTERVAL_MILLIS;
                }
            }
            if (tokens == 0) {
                return false;
            }
            tokens--;
            return true;
        }
    }
}
