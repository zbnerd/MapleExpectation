package maple.expectation.config;

import maple.expectation.global.executor.CheckedLogicExecutor;
import maple.expectation.global.executor.DefaultCheckedLogicExecutor;
import maple.expectation.global.executor.DefaultLogicExecutor;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.policy.ExecutionPipeline;
import maple.expectation.global.executor.policy.ExecutionPolicy;
import maple.expectation.global.executor.policy.LoggingPolicy;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import maple.expectation.aop.context.SkipEquipmentL2CacheContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableConfigurationProperties(ExecutorLoggingProperties.class)
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    // ========================================
    // Alert Executor Rejection Policy (P0-1 )
    // ========================================

    /** 로그 샘플링 간격: 1초에 1회만 WARN 로그 (log storm 방지) */
    private static final long REJECT_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * 인스턴스별 로그 샘플링 카운터 (#271 V5 P1 검토 완료)
     *
     * <h4>Stateless 검토 결과</h4>
     * <p>이 카운터들은 <b>로그 샘플링 용도</b>로, 정확한 클러스터 집계가 필요 없습니다:</p>
     * <ul>
     *   <li>목적: log storm 방지 (1초에 1회만 WARN)</li>
     *   <li>영향: 인스턴스별 독립 샘플링 → 정상 동작</li>
     *   <li>결론: Micrometer Counter로 교체 불필요 (이미 rejected Counter 별도 존재)</li>
     * </ul>
     *
     * <p>실제 rejected 메트릭은 {@code executor.rejected} Counter로 Micrometer에 집계됩니다.</p>
     */
    private static final AtomicLong lastRejectLogNanos = new AtomicLong(0);
    private static final AtomicLong rejectedSinceLastLog = new AtomicLong(0);

    /**
     * Best-effort + Future 완료 보장 정책 
     *
     * <h4>핵심 계약</h4>
     * <ul>
     *   <li><b>드롭 허용</b>: 알림은 best-effort이므로 큐 포화 시 드롭 가능</li>
     *   <li><b>Future 완료 보장</b>: CompletableFuture.runAsync가 "영원히 pending" 되는 것을 방지하기 위해
     *       RejectedExecutionException을 throw하여 Future가 exceptionally 완료되도록 함</li>
     *   <li><b>Log storm 방지</b>: 샘플링으로 1초에 1회만 WARN 로그</li>
     *   <li><b>Shutdown 시나리오</b>: 종료 중 거절은 정상이므로 로그 레벨 낮춤</li>
     * </ul>
     *
     * <h4>DiscardPolicy와의 차이</h4>
     * <p>DiscardPolicy는 조용히 드롭하여 runAsync Future가 영원히 pending됨 → 메모리 누수/관측성 누락</p>
     * <p>이 핸들러는 throw하여 Future가 완료되고, exceptionally()에서 처리 가능</p>
     */
    private static final RejectedExecutionHandler LOGGING_ABORT_POLICY = (r, executor) -> {
        // 종료 중 거절은 정상 시나리오이므로 로그 없이 즉시 throw
        if (executor.isShutdown() || executor.isTerminating()) {
            throw new RejectedExecutionException("AlertExecutor rejected (shutdown in progress)");
        }

        // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
        long dropped = rejectedSinceLastLog.incrementAndGet();
        long now = System.nanoTime();
        long prev = lastRejectLogNanos.get();

        if (now - prev >= REJECT_LOG_INTERVAL_NANOS && lastRejectLogNanos.compareAndSet(prev, now)) {
            long count = rejectedSinceLastLog.getAndSet(0);
            log.warn("[AlertExecutor] Task rejected (queue full). droppedInLastWindow={}, taskClass={}, poolSize={}, activeCount={}, queueSize={}",
                    count,
                    r.getClass().getName(),
                    executor.getPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size());
        }

        // ★ 핵심: throw하여 runAsync Future가 exceptionally 완료되도록 함
        throw new RejectedExecutionException("AlertExecutor queue full (dropped=" + dropped + ")");
    };

    // ========================================
    // Expectation Executor Rejection Policy (Issue #168)
    // ========================================

    /**
     * Expectation Executor용 샘플링 카운터 (AlertExecutor와 분리)
     *
     * <h4>#271 V5 P1 검토 완료</h4>
     * <p>로그 샘플링 전용 → Micrometer 교체 불필요 (executor.rejected Counter가 메트릭 담당)</p>
     */
    private static final AtomicLong expectationLastRejectNanos = new AtomicLong(0);
    private static final AtomicLong expectationRejectedSinceLastLog = new AtomicLong(0);

    /**
     * Expectation 계산 전용 AbortPolicy (Issue #168)
     *
     * <h4>CallerRunsPolicy 제거 이유</h4>
     * <ul>
     *   <li><b>톰캣 스레드 고갈</b>: 큐 포화 시 톰캣 스레드에서 작업 실행 → 전체 API 마비</li>
     *   <li><b>메트릭 불가</b>: rejected count = 0으로 보임 (서킷브레이커 동작 불가)</li>
     *   <li><b>SLA 위반</b>: 요청 처리 시간 비정상 증가</li>
     * </ul>
     *
     * <h4>AbortPolicy 적용 효과</h4>
     * <ul>
     *   <li><b>즉시 거부</b>: O(1) 시간 복잡도, 톰캣 스레드 보호</li>
     *   <li><b>503 응답</b>: GlobalExceptionHandler에서 Retry-After 헤더와 함께 반환</li>
     *   <li><b>메트릭 가시성</b>: Micrometer executor.rejected Counter로 모니터링</li>
     * </ul>
     *
     * <h4>⚠️ Write-Behind 패턴 주의</h4>
     * <p>이 정책은 <b>읽기 전용 작업에만</b> 적용하세요.
     * DB 저장 등 쓰기 작업에 적용하면 데이터 유실 위험!</p>
     */
    private static final RejectedExecutionHandler EXPECTATION_ABORT_POLICY = (r, executor) -> {
        // 종료 중 거절은 정상 시나리오
        if (executor.isShutdown() || executor.isTerminating()) {
            throw new RejectedExecutionException("ExpectationExecutor rejected (shutdown in progress)");
        }

        // 샘플링: 1초에 1회만 WARN 로그 (log storm 방지)
        long dropped = expectationRejectedSinceLastLog.incrementAndGet();
        long now = System.nanoTime();
        long prev = expectationLastRejectNanos.get();

        if (now - prev >= REJECT_LOG_INTERVAL_NANOS && expectationLastRejectNanos.compareAndSet(prev, now)) {
            long count = expectationRejectedSinceLastLog.getAndSet(0);
            log.warn("[ExpectationExecutor] Task rejected (queue full). " +
                            "droppedInLastWindow={}, poolSize={}, activeCount={}, queueSize={}",
                    count,
                    executor.getPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size());
        }

        // Future 완료 보장을 위해 예외 throw
        throw new RejectedExecutionException("ExpectationExecutor queue full (capacity exceeded)");
    };

    @Bean
    public ExceptionTranslator exceptionTranslator() {
        // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
        return ExceptionTranslator.defaultTranslator();
    }

    @Bean
    public LoggingPolicy loggingPolicy(ExecutorLoggingProperties props) {
        return new LoggingPolicy(props.slowMs());
    }

    @Bean
    @ConditionalOnMissingBean(ExecutionPipeline.class)
    public ExecutionPipeline executionPipeline(List<ExecutionPolicy> policies) {
        List<ExecutionPolicy> ordered = new ArrayList<>(policies);
        AnnotationAwareOrderComparator.sort(ordered);
        return new ExecutionPipeline(ordered);
    }

    /**
     * 비즈니스 레이어 기본 Executor (Primary)
     *
     * <p>서비스/도메인 내부에서 기본으로 주입되는 Executor입니다.
     * IO 경계에서는 {@link CheckedLogicExecutor}를 {@code @Qualifier("checkedLogicExecutor")}로 opt-in합니다.</p>
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(LogicExecutor.class)
    public LogicExecutor logicExecutor(ExecutionPipeline pipeline, ExceptionTranslator translator) {
        return new DefaultLogicExecutor(pipeline, translator);
    }

    /**
     * IO 경계 전용 CheckedLogicExecutor 빈 등록
     *
     * <p>파일 I/O, 네트워크 통신, 분산 락 등 checked 예외가 발생하는
     * IO 경계에서 try-catch 없이 예외를 처리합니다.</p>
     *
     * <h4>주입 패턴 (Qualifier 명시 권장)</h4>
     * <p>Lombok {@code @RequiredArgsConstructor}는 {@code @Qualifier}를 생성자 파라미터로
     * 전파하지 않을 수 있으므로, 명시적 생성자를 권장합니다:</p>
     * <pre>{@code
     * class ResilientNexonApiClient {
     *     private final CheckedLogicExecutor checkedExecutor;
     *
     *     ResilientNexonApiClient(
     *         @Qualifier("checkedLogicExecutor") CheckedLogicExecutor checkedExecutor
     *     ) {
     *         this.checkedExecutor = checkedExecutor;
     *     }
     * }
     * }</pre>
     */
    @Bean(name = "checkedLogicExecutor")
    @ConditionalOnMissingBean(CheckedLogicExecutor.class)
    public CheckedLogicExecutor checkedLogicExecutor(ExecutionPipeline pipeline) {
        return new DefaultCheckedLogicExecutor(pipeline);
    }

    // ==================== 불변식 3: ThreadLocal 전파 (P0-4/B2) ====================

    /**
     * MDC + SkipEquipmentL2CacheContext 전파용 TaskDecorator
     *
     * <p>불변식 3 준수: 모든 비동기 실행 지점에서 ThreadLocal 상태가 전파되어야 함</p>
     *
     * <h4>전파 대상</h4>
     * <ul>
     *   <li><b>MDC</b>: {@link maple.expectation.global.filter.MDCFilter#REQUEST_ID_KEY} 등 로깅 컨텍스트</li>
     *   <li><b>SkipEquipmentL2CacheContext</b>: L2 캐시 스킵 플래그</li>
     * </ul>
     *
     * <h4>MDCFilter 연계</h4>
     * <p>HTTP 요청 진입 시 {@link maple.expectation.global.filter.MDCFilter}가 설정한
     * requestId가 이 TaskDecorator를 통해 비동기 워커 스레드로 전파됩니다.</p>
     *
     * <h4>전파 원리 (snapshot/restore 패턴)</h4>
     * <ol>
     *   <li>호출 스레드에서 contextMap = MDC.getCopyOfContextMap(), snap = snapshot()</li>
     *   <li>워커 스레드 진입 시 MDC.setContextMap(contextMap), restore(snap)</li>
     *   <li>작업 완료 후 finally에서 MDC.clear(), restore(before)로 원복</li>
     * </ol>
     *
     * @return TaskDecorator 인스턴스
     * @see maple.expectation.global.filter.MDCFilter
     */
    @Bean
    public TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            // 1. 호출 스레드에서 현재 상태 캡처
            var mdcContextMap = org.slf4j.MDC.getCopyOfContextMap();
            String cacheContextSnap = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반

            return () -> {
                // 2. 워커 스레드에서 기존 상태 백업
                var mdcBefore = org.slf4j.MDC.getCopyOfContextMap();
                String cacheContextBefore = SkipEquipmentL2CacheContext.snapshot(); // V5: MDC 기반

                // 3. 캡처된 상태로 설정
                if (mdcContextMap != null) {
                    org.slf4j.MDC.setContextMap(mdcContextMap);
                } else {
                    org.slf4j.MDC.clear();
                }
                SkipEquipmentL2CacheContext.restore(cacheContextSnap);

                try {
                    runnable.run();
                } finally {
                    // 4. 작업 완료 후 원래 상태로 복원 (스레드풀 누수 방지)
                    if (mdcBefore != null) {
                        org.slf4j.MDC.setContextMap(mdcBefore);
                    } else {
                        org.slf4j.MDC.clear();
                    }
                    SkipEquipmentL2CacheContext.restore(cacheContextBefore);
                }
            };
        };
    }

    /**
     * 외부 알림(Discord/Slack 등) 전용 비동기 Executor
     *
     * <h4>설계 의도</h4>
     * <ul>
     *   <li><b>commonPool 분리</b>: 외부 I/O 지연이 앱 전반의 CompletableFuture에 전파되는 것을 방지</li>
     *   <li><b>Best-effort 알림</b>: 알림은 부가 기능이므로, 폭주 시 드롭/종료 시 즉시 종료</li>
     * </ul>
     *
     * <h4>운영 정책</h4>
     * <ul>
     *   <li><b>RejectedExecution</b>: AbortPolicy + 샘플링 로깅 + rejected 메트릭</li>
     *   <li><b>Shutdown</b>: 대기 없이 즉시 종료 (알림은 flush 불필요)</li>
     * </ul>
     *
     * <h4>Issue #168 수정사항</h4>
     * <ul>
     *   <li>Micrometer ExecutorServiceMetrics 등록</li>
     *   <li>rejected Counter 추가 (ExecutorServiceMetrics 미제공)</li>
     * </ul>
     */
    @Bean(name = "alertTaskExecutor")
    @ConditionalOnMissingBean(name = "alertTaskExecutor")
    public Executor alertTaskExecutor(
            TaskDecorator contextPropagatingDecorator,
            MeterRegistry meterRegistry) {

        // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
        Counter alertRejectedCounter = Counter.builder("executor.rejected")
                .tag("name", "alert")
                .description("Number of tasks rejected due to queue full")
                .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("alert-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator);

        // Best-effort 정책: 드롭 허용 + Future 완료 보장 + 메트릭 기록
        executor.setRejectedExecutionHandler((r, e) -> {
            alertRejectedCounter.increment();
            LOGGING_ABORT_POLICY.rejectedExecution(r, e);
        });

        // Shutdown 정책: 대기 없이 즉시 종료 (알림은 flush 불필요)
        executor.setWaitForTasksToCompleteOnShutdown(false);

        executor.initialize();

        // 🟥 Red 권고: Micrometer ExecutorServiceMetrics 등록
        new ExecutorServiceMetrics(
                executor.getThreadPoolExecutor(),
                "alert",
                Collections.emptyList()
        ).bindTo(meterRegistry);

        return executor;
    }

    // ==================== AI Task Executor (P0-1: Semaphore 제한 Virtual Thread) ====================

    /**
     * AI LLM 호출 전용 Executor (P0-1: 무제한 Virtual Thread → Semaphore 제한)
     *
     * <h4>문제</h4>
     * <p>AiSreService에서 Executors.newVirtualThreadPerTaskExecutor()를 인스턴스 필드로 직접 생성.
     * Bean이 아니므로 관리 불가, 동시성 무제한으로 대량 에러 시 수백 LLM 호출 → OOM 위험.</p>
     *
     * <h4>해결</h4>
     * <ul>
     *   <li>Semaphore(5)로 동시 LLM 호출 최대 5개 제한</li>
     *   <li>Virtual Thread 사용으로 I/O 대기 시 효율적</li>
     *   <li>Spring Bean으로 관리하여 라이프사이클 추적 가능</li>
     * </ul>
     */
    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        Semaphore semaphore = new Semaphore(5);
        Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        return runnable -> virtualThreadExecutor.execute(() -> {
            boolean acquired = false;
            try {
                acquired = semaphore.tryAcquire(10, TimeUnit.SECONDS);
                if (!acquired) {
                    log.warn("[AiTaskExecutor] Semaphore timeout - LLM 호출 동시성 한도 초과");
                    throw new RejectedExecutionException("AI task executor semaphore timeout");
                }
                runnable.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("AI task executor interrupted", e);
            } finally {
                if (acquired) {
                    semaphore.release();
                }
            }
        });
    }

    // ==================== Expectation compute 데드라인용 전용 Executor ====================

    /**
     * Expectation compute(파싱/계산/외부 호출 포함) 데드라인 강제를 위한 전용 Executor
     *
     * <h4>설계 의도</h4>
     * <ul>
     *   <li><b>30초 데드라인 강제</b>: CompletableFuture.orTimeout()과 함께 사용하여
     *       leader compute가 30초를 초과하면 TimeoutException으로 정리</li>
     *   <li><b>inFlight 누수 방지</b>: @Scheduled 백그라운드 정리 대신 실제 데드라인 강제</li>
     * </ul>
     *
     * <h4>Issue #168 수정사항</h4>
     * <ul>
     *   <li>CallerRunsPolicy → AbortPolicy (톰캣 스레드 고갈 방지)</li>
     *   <li>503 응답 + Retry-After 헤더 반환</li>
     *   <li>rejected Counter 추가 (ExecutorServiceMetrics 미제공)</li>
     * </ul>
     *
     * <h4>Micrometer 메트릭</h4>
     * <ul>
     *   <li>{@code executor.completed} - 완료된 작업 수</li>
     *   <li>{@code executor.active} - 현재 활성 스레드 수</li>
     *   <li>{@code executor.queued} - 큐에 대기 중인 작업 수</li>
     *   <li>{@code executor.rejected} - 거부된 작업 수 (커스텀)</li>
     * </ul>
     */
    @Bean(name = "expectationComputeExecutor")
    public Executor expectationComputeExecutor(
            TaskDecorator contextPropagatingDecorator,
            MeterRegistry meterRegistry) {

        // Context7 Best Practice: rejected Counter 등록 (ExecutorServiceMetrics 미제공)
        Counter expectationRejectedCounter = Counter.builder("executor.rejected")
                .tag("name", "expectation.compute")
                .description("Number of tasks rejected due to queue full")
                .register(meterRegistry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("expectation-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator);

        // Issue #168: CallerRunsPolicy → AbortPolicy + rejected 메트릭 기록
        executor.setRejectedExecutionHandler((r, e) -> {
            expectationRejectedCounter.increment();
            EXPECTATION_ABORT_POLICY.rejectedExecution(r, e);
        });

        // Graceful Shutdown: 진행 중인 계산 작업 완료 대기
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();

        // Context7 Best Practice: Micrometer ExecutorServiceMetrics 등록
        // 제공 메트릭: executor.completed, executor.active, executor.queued, executor.pool.size
        new ExecutorServiceMetrics(
                executor.getThreadPoolExecutor(),
                "expectation.compute",
                Collections.emptyList()
        ).bindTo(meterRegistry);

        return executor;
    }
}
