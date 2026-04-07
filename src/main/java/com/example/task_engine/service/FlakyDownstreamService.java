package com.example.task_engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.Random;

/**
 * =========================================================================
 *  FLAKY DOWNSTREAM SERVICE — Phase 5: Resilience & Retries
 * =========================================================================
 *  Real-world downstream services (databases, third-party APIs) fail
 *  transiently due to network dips or brief overload.
 *  Without retries: The user gets a 500 error immediately.
 *  With retries: We automatically try again, using exponential backoff so
 *  we don't accidentally DDoS the struggling downstream service.
 */
@Service
public class FlakyDownstreamService {

    private static final Logger log = LoggerFactory.getLogger(FlakyDownstreamService.class);
    private final Random random = new Random();

    /**
     * {@code @Retryable} config:
     * - maxAttempts = 3 (Initial try + 2 retries)
     * - backoff = @Backoff(delay = 1000, multiplier = 2.0)
     *   -> Wait 1s before retry 1
     *   -> Wait 2s before retry 2
     *   (Exponential backoff)
     */
    @Retryable(
            retryFor = { IllegalStateException.class },
            backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public String executeFlakyWork(String taskName, int workMs) throws InterruptedException {
        log.info("[FLAKY-SERVICE] Attempting work for task='{}'", taskName);
        
        // 50% chance to fail
        if (random.nextBoolean()) {
            log.warn("[FLAKY-SERVICE] Simulated network failure for task='{}'", taskName);
            throw new IllegalStateException("Transient Network Failure");
        }

        // Simulate successful work
        Thread.sleep(workMs);
        return "SUCCESS";
    }
}
