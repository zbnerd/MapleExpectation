package maple.expectation.aop.aspect;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.collector.PerformanceStatisticsCollector;
import maple.expectation.global.executor.LogicExecutor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 실행 시간 로깅 Aspect (코드 평탄화 적용)
 *
 * <h3>Before (try-finally 보일러플레이트)</h3>
 * <pre>{@code
 * public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
 *     long start = System.currentTimeMillis();
 *     try {
 *         return joinPoint.proceed();
 *     } finally {
 *         long executionTime = System.currentTimeMillis() - start;
 *         statsCollector.addTime(methodName, executionTime);
 *     }
 * }
 * }</pre>
 *
 * <h3>After (LogicExecutor.executeWithFinally 사용)</h3>
 * <pre>{@code
 * public Object logExecutionTime(ProceedingJoinPoint joinPoint) {
 *     String methodName = joinPoint.getSignature().toShortString();
 *     long start = System.currentTimeMillis();
 *
 *     return executor.executeWithFinally(
 *         joinPoint::proceed,
 *         () -> this.recordExecutionTime(methodName, start),
 *         "logExecutionTime:" + methodName
 *     );
 * }
 * }</pre>
 *
 * @see LogicExecutor
 * @since 1.0.0
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
     * <p>throws Throwable 제거, try-finally 블록 제거
     */
    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().toShortString();
        long start = System.currentTimeMillis();

        return executor.executeWithFinally(
            joinPoint::proceed,
            () -> this.recordExecutionTime(methodName, start),
            "logExecutionTime:" + methodName
        );
    }

    /**
     * 실행 시간 기록 (평탄화: 별도 메서드로 분리)
     *
     * @param methodName 메서드 이름
     * @param start 시작 시간
     */
    private void recordExecutionTime(String methodName, long start) {
        long executionTime = System.currentTimeMillis() - start;
        statsCollector.addTime(methodName, executionTime);
    }

    public String[] getStatistics(String testName) {
        return statsCollector.calculateStatistics(testName);
    }

    /**
     * 💡 수정: Micrometer 체계에서는 수동 reset이 권장되지 않으므로 삭제하거나
     * 기능을 비워둡니다. (Prometheus가 시간 흐름에 따라 관리하기 때문)
     */
    public void resetStatistics() {
        log.warn("🔄 Micrometer 통계는 수동으로 리셋되지 않습니다. Prometheus 대시보드를 확인하세요.");
    }

    @PreDestroy
    public void printFinalStatistics() {
        // ✅ 수정: 바뀐 메서드 시그니처에 맞춰 호출
        String[] stats = statsCollector.calculateStatistics("애플리케이션 전체 운영");
        log.info("========================================================");
        for (String stat : stats) {
            log.info(stat);
        }
        log.info("========================================================");
    }
}