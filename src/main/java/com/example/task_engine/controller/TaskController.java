package com.example.task_engine.controller;

import com.example.task_engine.model.TaskRequest;
import com.example.task_engine.model.TaskResponse;
import com.example.task_engine.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * =========================================================================
 * TASK CONTROLLER — The REST API layer
 * =========================================================================
 *
 * This is the entry point for all client requests.
 *
 * KEY DESIGN DECISION: @ResponseBody + CompletableFuture
 * When a Spring MVC controller method returns a CompletableFuture,
 * Spring does NOT block the Tomcat HTTP thread waiting for it to finish.
 * Instead, Tomcat's thread is released immediately, and the response is
 * written asynchronously when the CompletableFuture completes.
 *
 * This means Tomcat's 200 threads are NOT consumed for the 1-second
 * duration of each task. Without this, you'd hit Tomcat's thread pool
 * saturation before you even got to test your task pool.
 *
 * =========================================================================
 */
@RestController
@RequestMapping("/api")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * POST /api/tasks (Phase 2 — Unbounded, always accepts)
     *
     * Example body: {"taskName": "my-task", "simulatedWorkMs": 2000}
     *
     * No matter how many requests you send, this will always return 200.
     * Watch the latency distribution climb as the queue fills up.
     */
    @PostMapping("/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitTask(
            @RequestBody TaskRequest request) {
        return taskService.submit(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * POST /api/v2/tasks (Phase 3 — Bounded, returns 429 when full)
     *
     * Same body format, but this one enforces backpressure.
     * When the bounded queue (capacity=20) + running threads (10) = 30 tasks
     * are in-flight, task #31 gets an immediate HTTP 429 with Retry-After header.
     *
     * TRY THIS in hey:
     * hey -n 50 -c 50 -m POST -H "Content-Type: application/json" \
     * -d '{"taskName":"bounded","simulatedWorkMs":2000}' \
     * http://localhost:8080/api/v2/tasks
     *
     * You'll see: [200] for ~30, [429] for ~20 — returned instantly (< 5ms)
     */
    @PostMapping("/v2/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitBoundedTask(
            @RequestBody TaskRequest request) {
        return taskService.submitBounded(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * POST /api/v3/tasks (Phase 4 — Rate Limiting + Backpressure stacked)
     *
     * This endpoint has TWO defense layers:
     *
     * Layer 1 — Token Bucket (Rate Gate):
     * Bucket capacity = 20 tokens, refill rate = 10 tokens/sec.
     * If you send 30 requests instantly: 20 pass (bucket drains), 10 get 429.
     * After 1 second: 10 more tokens available. Sustained rate = 10 req/sec.
     *
     * Layer 2 — Bounded Queue (Capacity Gate):
     * If rate limiter passes the request but the bounded pool is full → 429.
     *
     * TRY THIS to see only the rate limiter firing (within queue capacity):
     * hey -n 30 -c 30 -m POST -H "Content-Type: application/json" \
     * -d '{"taskName":"rate-test","simulatedWorkMs":500}' \
     * http://localhost:8080/api/v3/tasks
     *
     * The first 20 pass (bucket), ~10 get 429 with cause=RATE_LIMIT_EXCEEDED.
     * Wait 1 second, send again: 10 more pass. This is the token replenishment.
     */
    @PostMapping("/v3/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitRateLimitedTask(
            @RequestBody TaskRequest request) {
        return taskService.submitRateLimited(request)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * POST /api/v4/tasks (Phase 5 — Resilience & Retries)
     *
     * The final production-ready endpoint.
     * Layer 1: Token Bucket Rate Limiting
     * Layer 2: Bounded Queue Backpressure
     * Layer 3: Exponential Backoff Retries
     *
     * The downstream service simulates a 50% chance of throwing a Network
     * Exception.
     * With @Retryable, Spring will automatically retry up to 3 times, waiting
     * 1 second, then 2 seconds (exponential backoff).
     *
     * Watch the logs when you hit this endpoint: you'll see failed attempts
     * logged, but the client will still eventually get a HTTP 200 SUCCESS
     * (as long as it succeeds within 3 tries).
     */
    @PostMapping("/v4/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitResilientTask(
            @RequestBody TaskRequest request) {
        return taskService.submitResilient(request)
                .thenApply(ResponseEntity::ok);
    }
}
