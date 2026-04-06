package com.example.task_engine.exception;

/**
 * Thrown when the task queue is full and the server cannot accept more work.
 *
 * WHY A CUSTOM EXCEPTION?
 *  When our bounded thread pool rejects a task, Java throws a
 *  RejectedExecutionException. We catch that and re-throw this custom
 *  exception, which our GlobalExceptionHandler maps to HTTP 429.
 *
 *  This gives the client explicit feedback: "I am overloaded — back off."
 *  Without backpressure, the client would keep sending and the server would
 *  silently queue until it ran out of heap and crashed.
 */
public class TaskQueueFullException extends RuntimeException {

    private final int queueCapacity;
    private final int currentQueueSize;

    public TaskQueueFullException(int queueCapacity, int currentQueueSize) {
        super("Task queue is full. capacity=%d, current=%d"
                .formatted(queueCapacity, currentQueueSize));
        this.queueCapacity = queueCapacity;
        this.currentQueueSize = currentQueueSize;
    }

    public int getQueueCapacity() { return queueCapacity; }
    public int getCurrentQueueSize() { return currentQueueSize; }
}
