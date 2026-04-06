package com.example.task_engine.exception;

/**
 * Thrown when the token bucket rate limiter has no remaining tokens.
 *
 * IMPORTANT: This is different from TaskQueueFullException (Phase 3).
 *
 *  TaskQueueFullException  → the QUEUE is full (too many tasks already accepted)
 *  RateLimitExceededException → the RATE is exceeded (too many requests per second)
 *
 *  Think of it like a nightclub:
 *   - Rate limit  = the bouncer at the door (10 people/minute max)
 *   - Backpressure = the capacity rule (max 30 people inside at once)
 *
 *  Rate limiting fires FIRST — before the task even enters the queue.
 */
public class RateLimitExceededException extends RuntimeException {

    private final int limitPerSecond;
    private final long retryAfterMs;

    public RateLimitExceededException(int limitPerSecond, long retryAfterMs) {
        super("Rate limit exceeded. Limit: %d req/sec. Retry after %dms."
                .formatted(limitPerSecond, retryAfterMs));
        this.limitPerSecond = limitPerSecond;
        this.retryAfterMs = retryAfterMs;
    }

    public int getLimitPerSecond() { return limitPerSecond; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
