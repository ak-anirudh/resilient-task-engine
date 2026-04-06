package com.example.task_engine.model;

import java.time.Instant;

/**
 * Represents the result returned after a task has been processed.
 *
 * WHY AN INSTANT TIMESTAMP?
 *  Under load, you'll see the gap between 'submittedAt' and 'completedAt'
 *  grow massively — that's your queue latency becoming visible.
 *  This is one of the most important things to watch in Phase 2 benchmarks.
 */
public record TaskResponse(
        String taskName,
        String executedByThread,   // which thread processed the task
        long queuedForMs,          // how long it waited in the queue (ms)
        long executionMs,          // how long the actual work took (ms)
        String status
) {}
