package com.example.task_engine.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * =========================================================================
 *  GLOBAL EXCEPTION HANDLER — Maps domain exceptions → HTTP responses
 * =========================================================================
 *
 *  We handle TWO distinct 429 scenarios with different messages:
 *
 *  1. TaskQueueFullException (Phase 3 — Backpressure):
 *     The thread pool's bounded queue is full.
 *     "I'm already processing 30 tasks — my queue is physically full."
 *     Retry-After: 2 seconds (rough time for the queue to drain some tasks)
 *
 *  2. RateLimitExceededException (Phase 4 — Rate Limiting):
 *     The token bucket has no tokens left.
 *     "You're sending too fast — slow down to 10 req/sec."
 *     Retry-After: precise ms until next token is available
 *
 *  Both use HTTP 429 but for DIFFERENT reasons — the client can distinguish
 *  them by reading the 'cause' field in the response body.
 *
 * =========================================================================
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // -------------------------------------------------------------------------
    // Phase 3: Queue full — backpressure from bounded executor
    // -------------------------------------------------------------------------
    @ExceptionHandler(TaskQueueFullException.class)
    public ResponseEntity<Map<String, Object>> handleQueueFull(TaskQueueFullException ex) {
        log.warn("[REJECTED-QUEUE] Queue full — capacity={} current={} — HTTP 429",
                ex.getQueueCapacity(), ex.getCurrentQueueSize());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body(Map.of(
                        "status", 429,
                        "cause", "QUEUE_FULL",
                        "message", "Task queue is at capacity. Please retry shortly.",
                        "queueCapacity", ex.getQueueCapacity(),
                        "queueCurrentSize", ex.getCurrentQueueSize(),
                        "timestamp", Instant.now().toString()
                ));
    }

    // -------------------------------------------------------------------------
    // Phase 4: Rate limit exceeded — token bucket exhausted
    // -------------------------------------------------------------------------
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        log.warn("[REJECTED-RATE] Token bucket empty — limit={}/sec retryAfter={}ms — HTTP 429",
                ex.getLimitPerSecond(), ex.getRetryAfterMs());

        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                // Retry-After in seconds (HTTP spec) — round up from ms
                .header("Retry-After", String.valueOf((ex.getRetryAfterMs() / 1000) + 1))
                .body(Map.of(
                        "status", 429,
                        "cause", "RATE_LIMIT_EXCEEDED",
                        "message", "You are sending requests too fast. Slow down to %d req/sec."
                                .formatted(ex.getLimitPerSecond()),
                        "limitPerSecond", ex.getLimitPerSecond(),
                        "retryAfterMs", ex.getRetryAfterMs(),
                        "timestamp", Instant.now().toString()
                ));
    }
}
