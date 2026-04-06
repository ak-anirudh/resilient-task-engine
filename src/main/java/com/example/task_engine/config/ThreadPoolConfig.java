package com.example.task_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

/**
 * =========================================================================
 *  PHASE 2: THE NAIVE THREAD POOL — What most developers start with
 * =========================================================================
 *
 * A ThreadPoolExecutor has 5 key parameters. Understanding each one is
 * fundamental to understanding every concurrency problem you will ever face:
 *
 *  1. corePoolSize    – Threads always kept alive, even when idle.
 *                       Think of these as your "permanent staff".
 *
 *  2. maximumPoolSize – The ceiling on threads when the queue is FULL.
 *                       Threads above corePoolSize are "temp workers" and
 *                       get destroyed after keepAliveTime if idle.
 *
 *  3. keepAliveTime   – How long a temp thread waits for work before dying.
 *
 *  4. workQueue       – Where tasks wait when all core threads are busy.
 *                       THIS IS THE MOST CRITICAL PARAMETER.
 *
 *                       LinkedBlockingQueue (unbounded) = ∞ queue depth.
 *                       Tasks pile up here silently. The maximumPoolSize
 *                       is NEVER reached because the queue never fills up.
 *                       This is the default used by Executors.newFixedThreadPool.
 *
 *  5. rejectedExecutionHandler – What happens when the pool can't accept work.
 *                       With an unbounded queue, this is NEVER triggered.
 *
 * THE PROBLEM:
 *  With an unbounded queue, under a traffic spike:
 *   - 10 tasks run concurrently (corePoolSize = 10)
 *   - Thousands of tasks pile up in the queue
 *   - Queue depth → memory consumption → OutOfMemoryError and death
 *   - Latency for task #10,000 = (9,999 × simulatedWorkMs) → minutes
 *   - No backpressure signal is sent to the caller — they just wait forever
 *
 * =========================================================================
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * PHASE 2: UNBOUNDED pool — the naive, dangerous default.
     *
     * This is equivalent to Executors.newFixedThreadPool(10) but written
     * explicitly so you can SEE every parameter and understand them.
     */
    @Bean(name = "naiveExecutor")
    public ExecutorService naiveExecutorService() {
        return new ThreadPoolExecutor(
                10,                              // corePoolSize
                10,                              // maximumPoolSize (never reached with unbounded queue)
                60L, TimeUnit.SECONDS,           // keepAliveTime (irrelevant here — size never exceeds core)
                new LinkedBlockingQueue<>()      // UNBOUNDED queue — the danger zone
        );
    }

    // =========================================================================
    //  PHASE 3: BOUNDED pool — The backpressure fix
    // =========================================================================
    //
    //  The only change from Phase 2 is replacing LinkedBlockingQueue (unbounded)
    //  with ArrayBlockingQueue(20) — a hard cap of 20 waiting tasks.
    //
    //  HOW THE MATH WORKS:
    //   - corePoolSize = 10 threads running concurrently
    //   - queue capacity = 20 waiting tasks
    //   - Total tasks in-flight at once = 30 (10 running + 20 waiting)
    //   - Task 31 → hits AbortPolicy → throws RejectedExecutionException
    //     → caught in TaskService → thrown as TaskQueueFullException
    //     → caught by GlobalExceptionHandler → returned as HTTP 429
    //
    //  NOW WATCH WHAT CHANGES IN BENCHMARKS:
    //   - Max latency is CAPPED: worst case = queue_capacity/corePoolSize * workMs
    //     = (20/10) * 2000ms = 4000ms max wait (not 10s like Phase 2)
    //   - Server memory stays bounded — no silent pile-up in an infinite queue
    //   - Clients get fast, actionable 429s instead of silent 10s timeouts
    //
    // =========================================================================
    @Bean(name = "boundedExecutor")
    public ExecutorService boundedExecutorService() {
        return new ThreadPoolExecutor(
                10,                              // corePoolSize
                10,                              // maximumPoolSize
                60L, TimeUnit.SECONDS,           // keepAliveTime
                new ArrayBlockingQueue<>(20),    // BOUNDED queue — max 20 waiting tasks
                new ThreadPoolExecutor.AbortPolicy() // Throw RejectedExecutionException when full
        );
    }
}
