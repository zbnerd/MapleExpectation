package maple.expectation.aop.aspect;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.collector.PerformanceStatisticsCollector;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 실행 시간 로깅 Aspect (TaskContext 및 평탄화 적용)
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingAspect {

    private final PerformanceStatisticsCollector statsCollector;
    private final LogicExecutor executor;

    /**
     * 메서드 실행 시간 로깅 (코드 평탄화 적용)
     *
     * <p>TaskContext를 통해 메트릭 카디널리티를 통제하며 체크 예외 노이즈를 제거합니다.
     */
    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().toShortString();
        long start = System.currentTimeMillis();

        // ✅ 수정: String 대신 TaskContext 사용 (Component="Logging", Operation="ExecutionTime")
        TaskContext context = TaskContext.of("Logging", "ExecutionTime", methodName);

        return executor.executeWithFinally(
                joinPoint::proceed,
                () -> this.recordExecutionTime(methodName, start),
                context
        );
    }

    /**
     * 실행 시간 기록 (평탄화: 별도 메서드로 분리)
     */
    private void recordExecutionTime(String methodName, long start) {
        long executionTime = System.currentTimeMillis() - start;
        statsCollector.addTime(methodName, executionTime);
    }

    public String[] getStatistics(String testName) {
        return statsCollector.calculateStatistics(testName);
    }

    public void resetStatistics() {
        log.warn("🔄 Micrometer 통계는 수동으로 리셋되지 않습니다. Prometheus 대시보드를 확인하세요.");
    }

    @PreDestroy
    public void printFinalStatistics() {
        String[] stats = statsCollector.calculateStatistics("애플리케이션 전체 운영");
        log.info("========================================================");
        for (String stat : stats) {
            log.info(stat);
        }
        log.info("========================================================");
    }
}