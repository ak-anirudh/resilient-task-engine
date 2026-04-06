package com.example.task_engine.model;

/**
 * Represents the payload sent by a client to submit a task.
 *
 * WHY A MODEL CLASS?
 *  Rather than just hitting an endpoint with no body, we accept a structured
 *  request. This lets us later attach metadata like task type, priority, etc.
 *  It also makes the Postman request body explicit and educational.
 */
public record TaskRequest(
        String taskName,
        int simulatedWorkMs   // how long this task should "work" (sleep)
) {}
