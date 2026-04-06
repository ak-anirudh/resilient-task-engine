# Resilient Task Engine

A robust, high-performance task execution engine built with **Spring Boot 4** and **Java 25**. This project serves as an educational showcase demonstrating the mechanics of backend scaling by iteratively solving real-world concurrency challenges: thread starvation, memory exhaustion, burst traffic, and downstream flakiness.

## Architecture & Evolution

The project is structured across 5 distinct evolutionary phases, each solving a critical scaling problem.

### Phase 1: Unbounded Execution (The Naive Approach)
- **Endpoint:** `POST /api/tasks` (from earlier tests, currently disabled)
- **Problem:** By default, offloading tasks to an unbounded `LinkedBlockingQueue` decouples Tomcat threads (preventing HTTP timeouts), but introduces a silent killer: **Memory Exhaustion**. If ingestion outpaces execution, the queue grows infinitely until the JVM crashes with an `OutOfMemoryError`. Latency also degrades massively as tasks wait in the queue for seconds or minutes.

### Phase 2 & 3: Backpressure (Bounded Queue)
- **Endpoint:** `POST /api/v2/tasks`
- **Solution:** Replaced the unbounded queue with an `ArrayBlockingQueue(20)` using an `AbortPolicy`. 
- **Why?** When the queue hits 20 waiting tasks (plus 10 actively running), the pool rejects further submissions. The API intercepts this rejection and safely returns `HTTP 429 Too Many Requests`.
- **Result:** The system strictly defines its capacity. It gracefully degrades under load, preventing memory leaks and capping maximum latency.

### Phase 4: Rate Limiting (Token Bucket)
- **Endpoint:** `POST /api/v3/tasks`
- **Solution:** Introduced a lock-free Token Bucket algorithm (`RateLimiterService`) using `AtomicInteger` and CAS loops. It allows 10 requests per second with a bucket capacity of 20.
- **Why both?** Backpressure protects the *server's memory* from burst floods, but a persistent high-frequency client can still monopolize the entire queue. Rate-limiting protects *fairness* and provides early rejection before a task even reaches the queue.
- **Result:** Two layers of defense. The token bucket drops sustained abuse, while the bounded queue absorbs temporary bursts.

### Phase 5: Resilience & Retries (Exponential Backoff)
- **Endpoint:** `POST /api/v4/tasks`
- **Solution:** Added Spring Retry (`@Retryable`) around downstream operations. Simulated a 50% failure rate for downstream APIs. The system automatically retries failed tasks with an exponential backoff (e.g., wait 1s, then 2s).
- **Result:** Transient network failures are completely hidden from the client, who receives a successful `HTTP 200` after the system successfully retries in the background.

## How to Test Locally

### Prerequisites
- Java 25 installed
- Maven (`./mvnw` included)
- `hey` (for load testing) or **Postman** (for manual testing)

### Running the Application
To boot the server on `localhost:8080`, simply run:
```bash
./mvnw spring-boot:run
```

### Option A: Using Postman (Manual Testing)
We have included a postman collection inside the repository so you can manually test the APIs.

1. Open Postman.
2. Click **Import** and select the `Resilient_Task_Engine.postman_collection.json` file found in the root of this repository.
3. Open the imported collection and run the requests. 
   - *Tip:* Run the `Phase 5` endpoint request multiple times and watch the backend logs. You will see instances where "Transient Network Failure" occurs, and Spring Retry handles it automatically!

### Option B: Using `hey` (Load Testing & Benchmarking)
To see the system defenses in action under stress, hit the Phase 4/5 endpoints with `hey` (or another load generator like Apache Benchmark / wrk):
```bash
# Send 30 concurrent requests instantly
hey -n 30 -c 30 -m POST -H "Content-Type: application/json" -d '{"taskName":"demo","simulatedWorkMs":500}' http://localhost:8080/api/v4/tasks
```
You will observe:
1. ~20 requests succeed (Token Bucket capacity allows them through).
2. ~10 requests immediately fail with `HTTP 429 Too Many Requests` (Rate limit exceeded).
3. Check the Spring Boot console output to see Spring Retry stepping in and transparently recovering tasks that randomly simulated a failure!

## Key Design Decisions
- **Lock-Free Concurrency:** Used `AtomicInteger` instead of `synchronized` blocks inside the Token Bucket to avoid expensive context switching under heavy load.
- **Domain Exceptions:** Centralized exception handling using `@RestControllerAdvice` (`GlobalExceptionHandler`) to ensure domain errors (`TaskQueueFullException`, `RateLimitExceededException`) cleanly translate into structured JSON HTTP responses.
- **CompletableFuture:** Ensured that Tomcat NIO threads are instantly released as soon as a request is handed off, allowing the web server to handle thousands of concurrent connections concurrently.
