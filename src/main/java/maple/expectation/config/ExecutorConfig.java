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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
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

    @Bean
    public ExceptionTranslator exceptionTranslator() {
        // DefaultLogicExecutor가 기본적으로 사용할 번역기를 지정합니다.
        return ExceptionTranslator.defaultTranslator();
    }

    @Bean
    public LoggingPolicy loggingPolicy(ExecutorLoggingProperties props) {
        return new LoggingPolicy(props.getSlowMs());
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
     * SkipEquipmentL2CacheContext 전파용 TaskDecorator
     *
     * <p>불변식 3 준수: 모든 비동기 실행 지점에서 ThreadLocal 상태가 전파되어야 함</p>
     *
     * <h4>전파 원리 (snapshot/restore 패턴)</h4>
     * <ol>
     *   <li>호출 스레드에서 snap = snapshot()</li>
     *   <li>워커 스레드 진입 시 before = snapshot(); restore(snap);</li>
     *   <li>작업 완료 후 finally에서 restore(before)로 원복</li>
     * </ol>
     *
     * @return TaskDecorator 인스턴스
     */
    @Bean
    public TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            // 1. 호출 스레드에서 현재 상태 캡처
            Boolean snap = SkipEquipmentL2CacheContext.snapshot();
            return () -> {
                // 2. 워커 스레드에서 기존 상태 백업 후 캡처된 상태로 설정
                Boolean before = SkipEquipmentL2CacheContext.snapshot();
                SkipEquipmentL2CacheContext.restore(snap);
                try {
                    runnable.run();
                } finally {
                    // 3. 작업 완료 후 원래 상태로 복원 (스레드풀 누수 방지)
                    SkipEquipmentL2CacheContext.restore(before);
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
     * <h4>운영 정책 </h4>
     * <ul>
     *   <li><b>RejectedExecution</b>: LOGGING_ABORT_POLICY 사용
     *     <ul>
     *       <li>드롭 허용: 큐(200) 초과 시 신규 알림 드롭</li>
     *       <li>Future 완료 보장: RejectedExecutionException throw → runAsync Future가 exceptionally 완료</li>
     *       <li>Log storm 방지: 1초에 1회만 WARN 로그 (샘플링)</li>
     *     </ul>
     *   </li>
     *   <li><b>Shutdown</b>: 대기 없이 즉시 종료 (알림은 flush 불필요)</li>
     * </ul>
     *
     * <h4>⚠️ DiscardPolicy 금지 이유</h4>
     * <p>DiscardPolicy는 조용히 드롭하여 CompletableFuture.runAsync()의 Future가 영원히 pending됨.
     * 이는 메모리 누수와 관측성 누락을 유발하므로 에서는 사용 금지.</p>
     */
    @Bean(name = "alertTaskExecutor")
    @ConditionalOnMissingBean(name = "alertTaskExecutor")
    public Executor alertTaskExecutor(TaskDecorator contextPropagatingDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("alert-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);

        // 불변식 3: ThreadLocal 전파 (P0-4/B2)
        executor.setTaskDecorator(contextPropagatingDecorator);

        // Best-effort 정책: 드롭 허용 + Future 완료 보장 + 샘플링 로깅
        executor.setRejectedExecutionHandler(LOGGING_ABORT_POLICY);

        // Shutdown 정책: 대기 없이 즉시 종료 (알림은 flush 불필요)
        executor.setWaitForTasksToCompleteOnShutdown(false);

        executor.initialize();
        return executor;
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
     * <h4>CallerRunsPolicy 사용 이유</h4>
     * <ul>
     *   <li>과부하 시에도 "리더 계산"이 진행되도록 드롭 대신 호출 스레드에서 실행</li>
     *   <li>Single-flight follower는 5초 timeout 정책 유지</li>
     * </ul>
     *
     * <h4>TaskDecorator 적용</h4>
     * <p>SkipEquipmentL2CacheContext 등 ThreadLocal 전파 보장</p>
     */
    @Bean(name = "expectationComputeExecutor")
    public Executor expectationComputeExecutor(TaskDecorator contextPropagatingDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("expectation-");
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(30);
        executor.setTaskDecorator(contextPropagatingDecorator);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
