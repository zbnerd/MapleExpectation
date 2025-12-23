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

        try {
            return joinPoint.proceed();
        } finally {
            long executionTime = System.currentTimeMillis() - start;
            statsCollector.addTime(executionTime);
        }
    }

    // 💡 인터페이스 단순화: 수집기에서 직접 통계를 가져옴
    public String[] getStatistics(String testName) {
        return statsCollector.calculateStatistics(testName);
    }

    public void resetStatistics() {
        statsCollector.reset();
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