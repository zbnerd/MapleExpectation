package maple.expectation.aop.aspect;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.collector.PerformanceStatisticsCollector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class LoggingAspect {

    private final PerformanceStatisticsCollector statsCollector;

    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        // ✅ 메서드 이름을 가져와서 통계의 구분값(testName)으로 사용합니다.
        String methodName = joinPoint.getSignature().toShortString();

        try {
            return joinPoint.proceed();
        } finally {
            long executionTime = System.currentTimeMillis() - start;
            // ✅ 수정: 이제 '어떤 메서드'의 소요 시간인지 이름을 함께 넘겨야 합니다.
            statsCollector.addTime(methodName, executionTime);
        }
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