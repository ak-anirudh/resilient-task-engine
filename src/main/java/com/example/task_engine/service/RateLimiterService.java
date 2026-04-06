package com.example.task_engine.service;

import com.example.task_engine.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * =========================================================================
 *  TOKEN BUCKET RATE LIMITER — Built from scratch
 * =========================================================================
 *
 *  CONCEPT: A bucket holds N tokens. Each incoming request takes one token.
 *  A background thread refills the bucket at a fixed rate.
 *  When the bucket is empty, new requests are rejected immediately.
 *
 *  WHY AtomicInteger instead of synchronized int?
 *   AtomicInteger uses CPU-level Compare-And-Swap (CAS) instructions.
 *   This means we can safely decrement the token count from many threads
 *   at once WITHOUT acquiring a lock. Under high concurrency, lock-free
 *   operations are dramatically faster — no threads block waiting.
 *
 *  WHY NOT use a library like Bucket4j?
 *   Bucket4j is excellent for production. But building this ourselves means
 *   you understand exactly what's happening. Bucket4j does the same thing
 *   with more features (distributed Redis support, precise nanosecond timing).
 *
 *  PARAMETERS (tunable via application.properties in a real system):
 *   capacity    = 20   → max burst: 20 requests can hit at once
 *   refillRate  = 10   → steady state: 10 requests/second allowed
 *   refillEvery = 100ms → tokens added 10 times/second (smooth refill)
 *   tokensPerRefill = refillRate / (1000/refillEvery) = 10 / 10 = 1 token/100ms
 *
 *  THE MATH:
 *   If sustained load = 10 req/sec exactly → bucket stays full → all pass
 *   If sustained load = 20 req/sec → bucket drains at 10 tokens/sec → 50% rejected
 *   If sustained load = 30 req/sec → bucket drains at 20 tokens/sec → 67% rejected
 *
 * =========================================================================
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final int CAPACITY         = 20;   // max burst size
    private static final int REFILL_RATE_PER_SEC = 10; // steady-state tokens per second
    private static final int REFILL_INTERVAL_MS  = 100; // how often to refill
    private static final int TOKENS_PER_REFILL   = REFILL_RATE_PER_SEC / (1000 / REFILL_INTERVAL_MS);
    // = 10 / 10 = 1 token every 100ms

    // AtomicInteger: thread-safe, lock-free token counter
    // Starts full (capacity) so the first burst is always served
    private final AtomicInteger tokens = new AtomicInteger(CAPACITY);

    // A single daemon thread refills the bucket on a schedule
    private final ScheduledExecutorService refillScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rate-limiter-refill");
                t.setDaemon(true); // dies with the JVM — no need to shut down manually
                return t;
            });

    public RateLimiterService() {
        // Schedule token refill every REFILL_INTERVAL_MS milliseconds
        refillScheduler.scheduleAtFixedRate(
                this::refill,
                REFILL_INTERVAL_MS,   // initial delay
                REFILL_INTERVAL_MS,   // period
                TimeUnit.MILLISECONDS
        );
        log.info("[RATE-LIMITER] Started — capacity={}, refillRate={}/sec, interval={}ms",
                CAPACITY, REFILL_RATE_PER_SEC, REFILL_INTERVAL_MS);
    }

    /**
     * Try to consume one token. If successful, the request is allowed.
     * If the bucket is empty, throw RateLimitExceededException.
     *
     * HOW CAS WORKS here (compareAndSet):
     *  1. Read current token count
     *  2. If tokens > 0: atomically set tokens = tokens - 1 (no lock!)
     *  3. If another thread changed tokens between step 1 and 2: retry (loop)
     *  4. If tokens == 0: reject
     */
    public void tryConsume() {
        int current;
        do {
            current = tokens.get();
            if (current == 0) {
                // Calculate how long until the next refill
                long msUntilRefill = REFILL_INTERVAL_MS;
                log.warn("[RATE-LIMITER] Bucket empty — rejecting request. Next refill in ~{}ms", msUntilRefill);
                throw new RateLimitExceededException(REFILL_RATE_PER_SEC, msUntilRefill);
            }
        } while (!tokens.compareAndSet(current, current - 1));
        // compareAndSet returns false if `current` was stale → loop retries

        log.debug("[RATE-LIMITER] Token consumed — remaining={}/{}", tokens.get(), CAPACITY);
    }

    /**
     * Refill the bucket by TOKENS_PER_REFILL, up to CAPACITY.
     * Called by the background scheduler every REFILL_INTERVAL_MS.
     *
     * We use a CAS loop here too to avoid races between the refill thread
     * and the many request threads consuming tokens simultaneously.
     */
    private void refill() {
        int current;
        int refilled;
        do {
            current = tokens.get();
            refilled = Math.min(CAPACITY, current + TOKENS_PER_REFILL);
        } while (!tokens.compareAndSet(current, refilled));

        if (refilled > current) {
            log.debug("[RATE-LIMITER] Refilled +{} tokens → {}/{}", refilled - current, refilled, CAPACITY);
        }
    }
}
