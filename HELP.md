# Technical Guide & Extended Project Walkthrough

This document serves as the definitive technical reference for the Resilient Task Engine. It combines architectural deep-dives, design rationales, benchmark results, and configuration guides.

---

## 🌍 Environment Setup

### Why Java 25?
Java 25 is the latest LTS-track release (as of 2026). It ships with:
- **Virtual threads** (stable since Java 21) — relevant for Phase 5.
- **Records** (Java 16+) — used for `TaskRequest` / `TaskResponse` to eliminate boilerplate.
- **Pattern matching for instanceof** — used in `TaskService` to safely cast `ExecutorService` → `ThreadPoolExecutor`.

### Why Spring Boot 4 + Maven?
- Spring Boot 4 targets Java 17+ and ships with Spring Framework 7.
- Maven's `spring-boot-starter-parent` manages all dependency versions.
- `spring-boot-starter-webmvc` provides embedded Tomcat and Jackson (JSON).

---

## 🚀 The Evolutionary Journey

### Phase 1 & 2: Naive Thread Pool (The Starting Point)

**The core abstraction: `ThreadPoolExecutor`**
Java's `ThreadPoolExecutor` uses a `workQueue` to hold tasks. A common pitfall is the `maximumPoolSize` parameter:
> [!IMPORTANT]
> `maximumPoolSize` is a red herring when using unbounded queues (like `LinkedBlockingQueue`). The pool only spawns threads above `corePoolSize` when the queue is **FULL**. Since an unbounded queue never fills, the pool stays at `corePoolSize` forever.

**Why `CompletableFuture` in the controller?**
```java
// ❌ BAD — Tomcat thread blocked for 2s
@PostMapping("/tasks")
public TaskResponse bad(@RequestBody TaskRequest req) {
    Thread.sleep(req.simulatedWorkMs()); 
    return response;
}

// ✅ GOOD — Tomcat thread freed immediately
@PostMapping("/tasks")
public CompletableFuture<ResponseEntity<TaskResponse>> good(@RequestBody TaskRequest req) {
    return taskService.submitUnbounded(req).thenApply(ResponseEntity::ok);
}
```
Tomcat's thread pool is limited (~50-200 threads). Freeing them immediately allows the web server to accept new connections while our internal 10-thread pool handles the heavy lifting.

**Phase 2 Benchmark (Unbounded Queue)**
*   **10-thread pool | 50 concurrent requests | 2000ms work**
*   Total time: ~10 sec (5 rounds of 10 tasks)
*   Fastest: 2.18 sec | 90th pct: 10.17 sec.
*   **Danger:** With 10,000 requests, the queue grows infinitely, causing `OutOfMemoryError` (OOM) as each task holds request context in the heap.

### Phase 3: Backpressure with Bounded Queue
We replaced `LinkedBlockingQueue` with `ArrayBlockingQueue(20)`.

**The Rejection Chain:**
1. Task #31 arrives.
2. Pool checks: Queue full (20/20) AND all threads busy (10/10).
3. `AbortPolicy` fires → `RejectedExecutionException`.
4. Caught in `TaskService` → `TaskQueueFullException`.
5. `GlobalExceptionHandler` returns **HTTP 429** with `Retry-After: 2`.

**Capacity Math:**
*   Max in-flight = `corePoolSize` (10) + `queueCapacity` (20) = 30 tasks.
*   Max latency = (20 / 10) × 2s = 4000ms.
*   **Result:** Predictable latency and bounded memory usage.

### Phase 4: Rate Limiting (Token Bucket)
Rate limiting protects against sustained high-frequency abuse by providing early rejection before a task even reaches the internal execution queue.

| Feature | Backpressure (Phase 3) | Rate Limiting (Phase 4) |
|---|---|---|
| **Firing Point** | After queue fills | Before queueing |
| **Protection** | Server memory | Sustained throughput budget |
| **Shape** | Burst-friendly | Enforces steady rate (e.g. 10/s) |

**Algorithm:** We use a lock-free `AtomicInteger` with CAS loops to maintain a token bucket that refills every 100ms.

### Phase 5: Resilience & Retries
Added Spring Retry (`@Retryable`) around downstream operations to handle transient failures.

**Exponential Backoff Logic:**
*   **Attempt 1**: Fails (50% simulated chance).
*   *Wait 1000ms...*
*   **Attempt 2**: Fails.
*   *Wait 2000ms (multiplier 2.0)...*
*   **Attempt 3**: Succeeds!

---

## 🧠 Key Mental Models

### Thread Starvation
Occurs when more threads block waiting for I/O than your pool has capacity for. Threads aren't destroyed — they're occupied holding lock state or stuck on network I/O.
*Fix:* More threads, or non-blocking I/O (Virtual Threads).

### Little’s Law (`L = λ × W`)
Arrival rate (`λ`) exceeding system throughput eventually causes the queue to grow unboundedly. Bounding `L` (tasks in system) is the only way to stabilize `W` (wait time).

---

## 🛠 Configuration & Observability

### Tuning Settings
*   **`ThreadPoolConfig.java`**: Core pool (10) and Queue cap (20).
*   **`RateLimiterService.java`**: Bucket capacity (20) and Refill (10/s).

### Observability (Spring Actuator)
Monitor real-time engine health:
*   [Health Check](http://localhost:8080/actuator/health)
*   **Active Threads**: `actuator/metrics/executor.active`
*   **Queue Depth**: `actuator/metrics/executor.queued`

---

## ❓ Troubleshooting FAQ

1.  **429 even when idle?** Check the `cause` field. `RATE_LIMIT_EXCEEDED` means you hit the fairness quota, not the physical capacity.
2.  **High Task Latency?** Check logs for `[RETRY]`. Exponential backoffs (1s + 2s + 4s...) add up if downstream services are shaky.
3.  **OutOfMemoryError?** Ensure you are using the v2, v3, or v4 endpoints. Avoid v1 (unbounded) for high-volume traffic.
