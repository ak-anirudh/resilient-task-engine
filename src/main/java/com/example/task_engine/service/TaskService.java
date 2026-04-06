package com.example.task_engine.service;

import com.example.task_engine.exception.TaskQueueFullException;
import com.example.task_engine.model.TaskRequest;
import com.example.task_engine.model.TaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

/**
 * TASK SERVICE
 * Supports unbounded, bounded (backpressure), rate-limited, and resilient tasks.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ExecutorService naiveExecutor;
    private final ExecutorService boundedExecutor;
    private final RateLimiterService rateLimiter;
    private final FlakyDownstreamService flakyDownstreamService;

    public TaskService(
            @Qualifier("naiveExecutor") ExecutorService naiveExecutor,
            @Qualifier("boundedExecutor") ExecutorService boundedExecutor,
            RateLimiterService rateLimiter,
            FlakyDownstreamService flakyDownstreamService
    ) {
        this.naiveExecutor = naiveExecutor;
        this.boundedExecutor = boundedExecutor;
        this.rateLimiter = rateLimiter;
        this.flakyDownstreamService = flakyDownstreamService;
    }

    public CompletableFuture<TaskResponse> submitUnbounded(TaskRequest request) {
        return submitToPool(request, naiveExecutor);
    }

    public CompletableFuture<TaskResponse> submitBounded(TaskRequest request) {
        // Snapshot queue state BEFORE attempting submission
        int queueSize = 0, queueCapacity = 20;
        if (boundedExecutor instanceof ThreadPoolExecutor tpe) {
            queueSize = tpe.getQueue().size();
            queueCapacity = tpe.getQueue().size() + tpe.getQueue().remainingCapacity();

            log.info("[SUBMIT-BOUNDED] task='{}' | queue={}/{} | active={}/{}",
                    request.taskName(),
                    queueSize, queueCapacity,
                    tpe.getActiveCount(), tpe.getCorePoolSize()
            );
        }

        try {
            return submitToPool(request, boundedExecutor);
        } catch (RejectedExecutionException e) {
            // Queue is full — surface this as a 429, not a 500
            throw new TaskQueueFullException(queueCapacity, queueSize);
        }
    }

    public CompletableFuture<TaskResponse> submitRateLimited(TaskRequest request) {
        // This throws RateLimitExceededException if the bucket is empty.
        // The exception propagates up to GlobalExceptionHandler → HTTP 429
        rateLimiter.tryConsume();

        // Token consumed successfully — now attempt to queue the task
        // This may still throw TaskQueueFullException if the pool is full
        return submitBounded(request);
    }

    public CompletableFuture<TaskResponse> submitResilient(TaskRequest request) {
        // Full defense:
        // 1. Rate limiter
        rateLimiter.tryConsume();

        // 2. Queue capacity bound
        // 3. Retryable downstream execution (passed via boolean flag to internal logic)
        return submitToPool(request, boundedExecutor, true);
    }

    private CompletableFuture<TaskResponse> submitToPool(TaskRequest request, ExecutorService pool) {
        return submitToPool(request, pool, false);
    }

    private CompletableFuture<TaskResponse> submitToPool(TaskRequest request, ExecutorService pool, boolean useFlakyService) {
        long submittedAt = System.currentTimeMillis();

        if (pool instanceof ThreadPoolExecutor tpe) {
            log.info("[SUBMIT] task='{}' | queue_depth={} | active_threads={}/{}",
                    request.taskName(),
                    tpe.getQueue().size(),
                    tpe.getActiveCount(),
                    tpe.getCorePoolSize()
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            long queuedForMs = System.currentTimeMillis() - submittedAt;
            String threadName = Thread.currentThread().getName();

            log.info("[EXECUTE] task='{}' | waited_in_queue={}ms | thread='{}'",
                    request.taskName(), queuedForMs, threadName);

            long workStart = System.currentTimeMillis();
            String executionResultStatus = "COMPLETED";
            try {
                if (useFlakyService) {
                    // Uses Spring Retry under the hood — might fail a few times before succeeding
                    executionResultStatus = flakyDownstreamService.executeFlakyWork(request.taskName(), request.simulatedWorkMs());
                } else {
                    // Raw sleep (no retries)
                    Thread.sleep(request.simulatedWorkMs());
                }
            } catch (IllegalStateException e) {
                // Occurs if FlakyDownstreamService exhaused all 3 retries
                log.error("[FAILED] task='{}' permanently failed after retries: {}", request.taskName(), e.getMessage());
                executionResultStatus = "FAILED_PERMANENTLY";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new TaskResponse(
                        request.taskName(), threadName,
                        queuedForMs, 0, "INTERRUPTED"
                );
            }
            long executionMs = System.currentTimeMillis() - workStart;

            log.info("[DONE] task='{}' | exec={}ms | total_time={}ms | status={}",
                    request.taskName(), executionMs, queuedForMs + executionMs, executionResultStatus);

            return new TaskResponse(
                    request.taskName(), threadName,
                    queuedForMs, executionMs, executionResultStatus
            );
        }, pool);
    }
}
