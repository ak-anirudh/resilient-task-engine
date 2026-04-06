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

    @PostMapping("/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitTask(
            @RequestBody TaskRequest request) {
        return taskService.submitUnbounded(request)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/v2/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitBoundedTask(
            @RequestBody TaskRequest request) {
        return taskService.submitBounded(request)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/v3/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitRateLimitedTask(
            @RequestBody TaskRequest request) {
        return taskService.submitRateLimited(request)
                .thenApply(ResponseEntity::ok);
    }

    @PostMapping("/v4/tasks")
    public CompletableFuture<ResponseEntity<TaskResponse>> submitResilientTask(
            @RequestBody TaskRequest request) {
        return taskService.submitResilient(request)
                .thenApply(ResponseEntity::ok);
    }
}
