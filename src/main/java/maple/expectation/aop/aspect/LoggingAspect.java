package maple.expectation.aop.aspect;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.aop.collector.PerformanceStatisticsCollector;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.List;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor // 수집기 주입을 위함
public class LoggingAspect {

    private final PerformanceStatisticsCollector statsCollector;

    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        try {
            return joinPoint.proceed();
        } finally {
            // 메서드 실행이 성공하든 실패하든 실행 시간 수집
            long executionTime = System.currentTimeMillis() - start;
            statsCollector.addTime(executionTime);
        }
    }

    /**
     * 테스트 코드 등 외부에서 호출할 때 사용
     */
    public List<Long> getAndClearExecutionTimes() {
        return statsCollector.getAndClear();
    }

    public String[] calculateStatistics(List<Long> times, String testName) {
        return statsCollector.calculateStatistics(times, testName);
    }

    @PreDestroy
    public void printFinalStatistics() {
        // 애플리케이션 종료 전 최종 통계 출력
        List<Long> times = statsCollector.getAndClear();
        String[] stats = statsCollector.calculateStatistics(times, "전체 성능 통계");

        log.info("========================================================");
        for (String stat : stats) {
            log.info(stat);
        }
        log.info("========================================================");
    }
}