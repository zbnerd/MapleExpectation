package maple.expectation.chaos.nightmare;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nightmare 03: Thread Pool Exhaustion
 *
 * <h4>5-Agent Council</h4>
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - 대량 비동기 작업으로 Thread Pool 포화</li>
 *   <li>🔵 Blue (Architect): 흐름 검증 - CallerRunsPolicy vs AbortPolicy 동작</li>
 *   <li>🟢 Green (Performance): 메트릭 검증 - 작업 제출 시간, 블로킹 여부</li>
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 작업 손실 여부</li>
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 블로킹 발생 시 P1 Issue 생성</li>
 * </ul>
 *
 * <h4>예상 결과: FAIL</h4>
 * <p>CallerRunsPolicy 설정 시 큐 포화로 인해 제출 스레드(메인 스레드)에서
 * 작업이 직접 실행되어 블로킹 발생.</p>
 *
 * <h4>관련 CS 원리</h4>
 * <ul>
 *   <li>Thread Pool Saturation: 풀 포화로 인한 병목</li>
 *   <li>Backpressure: 과부하 시 제어 흐름</li>
 *   <li>RejectedExecutionHandler 전략:
 *       <ul>
 *         <li>CallerRunsPolicy: 호출자 스레드에서 실행 (블로킹)</li>
 *         <li>AbortPolicy: RejectedExecutionException 발생</li>
 *         <li>DiscardPolicy: 작업 손실 (무시)</li>
 *         <li>DiscardOldestPolicy: 가장 오래된 작업 교체</li>
 *       </ul>
 *   </li>
 *   <li>Little's Law: L = λW (대기열 길이 = 도착률 × 대기 시간)</li>
 * </ul>
 *
 * @see java.util.concurrent.ThreadPoolExecutor
 * @see org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 03: Thread Pool Exhaustion")
class ThreadPoolExhaustionNightmareTest extends AbstractContainerBaseTest {

    private static final int SMALL_POOL_SIZE = 2;
    private static final int SMALL_QUEUE_SIZE = 2;
    private static final long TASK_DURATION_MS = 2000; // 각 작업 2초 소요

    /**
     * 테스트용 소규모 Thread Pool 설정
     * - corePoolSize: 2
     * - maxPoolSize: 2
     * - queueCapacity: 2
     * - rejectedExecutionHandler: CallerRunsPolicy (블로킹 유발)
     */
    @TestConfiguration
    @EnableAsync
    static class TestConfig {

        @Bean(name = "nightmareExecutor")
        public ThreadPoolTaskExecutor nightmareExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(SMALL_POOL_SIZE);
            executor.setMaxPoolSize(SMALL_POOL_SIZE);
            executor.setQueueCapacity(SMALL_QUEUE_SIZE);
            executor.setThreadNamePrefix("nightmare-");
            // CallerRunsPolicy: 큐 포화 시 호출 스레드에서 직접 실행 → 블로킹!
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
            executor.initialize();
            return executor;
        }

        @Bean(name = "abortPolicyExecutor")
        public ThreadPoolTaskExecutor abortPolicyExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(SMALL_POOL_SIZE);
            executor.setMaxPoolSize(SMALL_POOL_SIZE);
            executor.setQueueCapacity(SMALL_QUEUE_SIZE);
            executor.setThreadNamePrefix("abort-");
            // AbortPolicy: 큐 포화 시 RejectedExecutionException 발생
            executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
            executor.initialize();
            return executor;
        }
    }

    @Autowired(required = false)
    @Qualifier("nightmareExecutor")
    private ThreadPoolTaskExecutor nightmareExecutor;

    @Autowired(required = false)
    @Qualifier("abortPolicyExecutor")
    private ThreadPoolTaskExecutor abortPolicyExecutor;

    /**
     * 🔴 Red's Test 1: CallerRunsPolicy로 인한 메인 스레드 블로킹 검증
     *
     * <p><b>시나리오</b>:
     * <ol>
     *   <li>Pool Size(2) + Queue Size(2) = 최대 4개 동시 처리</li>
     *   <li>10개 작업 제출 (각 2초 소요)</li>
     *   <li>5번째 작업부터 CallerRunsPolicy 발동 → 메인 스레드 블로킹</li>
     *   <li>제출 완료 시간 측정 (블로킹 시 > 10초)</li>
     * </ol>
     *
     * <p><b>성공 기준</b>: 작업 제출 시간 < 100ms</p>
     * <p><b>실패 조건</b>: 작업 제출 시간 > 100ms (CallerRunsPolicy 블로킹)</p>
     */
    @Test
    @DisplayName("CallerRunsPolicy로 인한 메인 스레드 블로킹 검증")
    void shouldDetectMainThreadBlocking_withCallerRunsPolicy() throws Exception {
        // 테스트용 executor 직접 생성 (Spring Context 의존 제거)
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SMALL_POOL_SIZE);
        executor.setMaxPoolSize(SMALL_POOL_SIZE);
        executor.setQueueCapacity(SMALL_QUEUE_SIZE);
        executor.setThreadNamePrefix("nightmare-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        int taskCount = 10; // Pool(2) + Queue(2) = 4, 초과 6개는 CallerRunsPolicy 발동
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger callerRunsCount = new AtomicInteger(0);
        List<Long> submitTimes = new CopyOnWriteArrayList<>();

        String mainThreadName = Thread.currentThread().getName();

        log.info("[Red] Starting Thread Pool Exhaustion test...");
        log.info("[Red] Pool Size: {}, Queue Size: {}", SMALL_POOL_SIZE, SMALL_QUEUE_SIZE);
        log.info("[Red] Task Count: {}, Task Duration: {}ms", taskCount, TASK_DURATION_MS);
        log.info("[Red] Main Thread: {}", mainThreadName);

        long totalStartTime = System.nanoTime();

        // When: 대량 작업 제출
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            long submitStart = System.nanoTime();

            executor.execute(() -> {
                String currentThread = Thread.currentThread().getName();
                if (currentThread.equals(mainThreadName) || !currentThread.startsWith("nightmare-")) {
                    callerRunsCount.incrementAndGet();
                    log.info("[Red] Task {}: CallerRunsPolicy triggered! (Thread: {})",
                            taskId, currentThread);
                }

                try {
                    Thread.sleep(TASK_DURATION_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completedCount.incrementAndGet();
            });

            long submitTime = (System.nanoTime() - submitStart) / 1_000_000;
            submitTimes.add(submitTime);
            submittedCount.incrementAndGet();

            if (submitTime > 100) {
                log.info("[Red] Task {}: Submit blocked for {}ms!", taskId, submitTime);
            }
        }

        long totalSubmitTime = (System.nanoTime() - totalStartTime) / 1_000_000;

        // 모든 작업 완료 대기
        executor.shutdown();
        boolean terminated = executor.getThreadPoolExecutor().awaitTermination(60, TimeUnit.SECONDS);

        // Then: 분석
        long maxSubmitTime = submitTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        long avgSubmitTime = submitTimes.stream().mapToLong(Long::longValue).sum() / submitTimes.size();
        long blockedSubmits = submitTimes.stream().filter(t -> t > 100).count();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│      Nightmare 03: Thread Pool Exhaustion Results          │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Pool Size: {}, Queue Size: {}                               │",
                SMALL_POOL_SIZE, SMALL_QUEUE_SIZE);
        log.info("│ Tasks Submitted: {}                                         │", submittedCount.get());
        log.info("│ Tasks Completed: {}                                         │", completedCount.get());
        log.info("│ Terminated: {}                                              │", terminated ? "YES" : "NO");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Total Submit Time: {}ms                                     │", totalSubmitTime);
        log.info("│ Avg Submit Time: {}ms                                       │", avgSubmitTime);
        log.info("│ Max Submit Time: {}ms                                       │", maxSubmitTime);
        log.info("│ Blocked Submits (>100ms): {}                                │", blockedSubmits);
        log.info("│ CallerRunsPolicy Triggered: {} times                        │", callerRunsCount.get());
        log.info("├────────────────────────────────────────────────────────────┤");

        if (blockedSubmits > 0 || callerRunsCount.get() > 0) {
            log.info("│ ❌ MAIN THREAD BLOCKED!                                    │");
            log.info("│ 🔧 Solution: Increase pool/queue size or use AbortPolicy   │");
        } else {
            log.info("│ ✅ No blocking detected                                    │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // 검증: 작업 제출이 100ms 이내에 완료되어야 함 (비블로킹)
        // CallerRunsPolicy 사용 시 FAIL 예상
        assertThat(maxSubmitTime)
                .as("[Nightmare] 작업 제출은 메인 스레드를 블로킹하지 않아야 함 (≤100ms)")
                .isLessThanOrEqualTo(100L);
    }

    /**
     * 🔵 Blue's Test 2: AbortPolicy 사용 시 RejectedExecutionException 발생 검증
     */
    @Test
    @DisplayName("AbortPolicy 사용 시 RejectedExecutionException 발생")
    void shouldThrowRejectedExecutionException_withAbortPolicy() throws Exception {
        // 테스트용 executor 직접 생성
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SMALL_POOL_SIZE);
        executor.setMaxPoolSize(SMALL_POOL_SIZE);
        executor.setQueueCapacity(SMALL_QUEUE_SIZE);
        executor.setThreadNamePrefix("abort-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        int taskCount = 10;
        AtomicInteger submittedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        log.info("[Blue] Testing AbortPolicy behavior...");

        long startTime = System.nanoTime();

        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            try {
                executor.execute(() -> {
                    try {
                        Thread.sleep(TASK_DURATION_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                submittedCount.incrementAndGet();
            } catch (RejectedExecutionException e) {
                rejectedCount.incrementAndGet();
                log.info("[Blue] Task {} rejected: {}", taskId, e.getMessage());
            }
        }

        long submitTime = (System.nanoTime() - startTime) / 1_000_000;

        executor.shutdown();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│           AbortPolicy Behavior Analysis                    │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Tasks Attempted: {}                                         │", taskCount);
        log.info("│ Tasks Submitted: {}                                         │", submittedCount.get());
        log.info("│ Tasks Rejected: {}                                          │", rejectedCount.get());
        log.info("│ Submit Time: {}ms                                           │", submitTime);
        log.info("├────────────────────────────────────────────────────────────┤");

        if (rejectedCount.get() > 0) {
            log.info("│ ✅ AbortPolicy correctly rejected excess tasks             │");
            log.info("│ ⚠️ But task loss occurred!                                │");
        } else {
            log.info("│ ⚠️ No rejections - pool/queue was large enough            │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // AbortPolicy는 초과 작업을 거부해야 함
        // Pool(2) + Queue(2) = 4개만 수용, 나머지 6개 거부 예상
        assertThat(rejectedCount.get())
                .as("AbortPolicy는 초과 작업을 거부해야 함")
                .isGreaterThan(0);

        // 제출은 빠르게 완료되어야 함 (블로킹 없음)
        assertThat(submitTime)
                .as("AbortPolicy는 블로킹 없이 빠르게 거부해야 함")
                .isLessThan(500);
    }

    /**
     * 🟢 Green's Test 3: Thread Pool 메트릭 분석
     */
    @Test
    @DisplayName("Thread Pool 메트릭 실시간 분석")
    void shouldAnalyzeThreadPoolMetrics_inRealTime() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SMALL_POOL_SIZE);
        executor.setMaxPoolSize(SMALL_POOL_SIZE);
        executor.setQueueCapacity(SMALL_QUEUE_SIZE);
        executor.setThreadNamePrefix("metrics-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        int taskCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(taskCount);

        log.info("[Green] Monitoring Thread Pool metrics...");
        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│ Time │ Active │ Pool │ Queue │ Completed │ Status         │");
        log.info("├────────────────────────────────────────────────────────────┤");

        // 메트릭 수집 스레드
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger tick = new AtomicInteger(0);
        AtomicLong maxQueueSize = new AtomicLong(0);

        monitor.scheduleAtFixedRate(() -> {
            ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
            int active = pool.getActiveCount();
            int poolSize = pool.getPoolSize();
            int queueSize = pool.getQueue().size();
            long completed = pool.getCompletedTaskCount();

            maxQueueSize.set(Math.max(maxQueueSize.get(), queueSize));

            String status;
            if (queueSize >= SMALL_QUEUE_SIZE) {
                status = "⚠️ QUEUE FULL";
            } else if (active >= SMALL_POOL_SIZE) {
                status = "🔶 POOL BUSY";
            } else {
                status = "✅ NORMAL";
            }

            log.info("│ T+{}s │ {}      │ {}    │ {}     │ {}         │ {} │",
                    tick.incrementAndGet(), active, poolSize, queueSize, completed, status);
        }, 0, 500, TimeUnit.MILLISECONDS);

        // 작업 제출
        for (int i = 0; i < taskCount; i++) {
            final int taskId = i;
            executor.execute(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(1000); // 1초 작업
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);

        monitor.shutdown();
        executor.shutdown();

        log.info("└────────────────────────────────────────────────────────────┘");
        log.info("Max Queue Size observed: {} (capacity: {})", maxQueueSize.get(), SMALL_QUEUE_SIZE);

        // Queue가 가득 찬 상황이 발생해야 함
        assertThat(maxQueueSize.get())
                .as("테스트 중 Queue가 가득 차야 함 (포화 상태 검증)")
                .isGreaterThanOrEqualTo(SMALL_QUEUE_SIZE);
    }

    /**
     * 🟣 Purple's Test 4: 작업 손실 여부 검증 (DiscardPolicy)
     */
    @Test
    @DisplayName("DiscardPolicy 사용 시 작업 손실 검증")
    void shouldDetectTaskLoss_withDiscardPolicy() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(SMALL_POOL_SIZE);
        executor.setMaxPoolSize(SMALL_POOL_SIZE);
        executor.setQueueCapacity(SMALL_QUEUE_SIZE);
        executor.setThreadNamePrefix("discard-");
        // DiscardPolicy: 초과 작업을 조용히 버림 (경고 없음!)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();

        int taskCount = 10;
        AtomicInteger executedCount = new AtomicInteger(0);

        log.info("[Purple] Testing DiscardPolicy (silent task loss)...");

        for (int i = 0; i < taskCount; i++) {
            executor.execute(() -> {
                try {
                    Thread.sleep(500);
                    executedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS);

        int lostTasks = taskCount - executedCount.get();

        log.info("┌────────────────────────────────────────────────────────────┐");
        log.info("│           DiscardPolicy Task Loss Analysis                 │");
        log.info("├────────────────────────────────────────────────────────────┤");
        log.info("│ Tasks Submitted: {}                                         │", taskCount);
        log.info("│ Tasks Executed: {}                                          │", executedCount.get());
        log.info("│ Tasks Lost: {}                                              │", lostTasks);
        log.info("├────────────────────────────────────────────────────────────┤");

        if (lostTasks > 0) {
            log.info("│ ⚠️ DATA LOSS DETECTED!                                    │");
            log.info("│ 🔧 Never use DiscardPolicy for critical tasks             │");
        } else {
            log.info("│ ✅ No task loss (pool was sufficient)                     │");
        }
        log.info("└────────────────────────────────────────────────────────────┘");

        // DiscardPolicy는 작업을 조용히 버리므로 손실 발생
        assertThat(lostTasks)
                .as("DiscardPolicy는 작업 손실을 유발함 (위험!)")
                .isGreaterThan(0);
    }
}
