package maple.expectation.aop.aspect; // 패키지 변경됨

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;

@Aspect
@Component
@Slf4j
public class LoggingAspect {

    private final List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());

    /**
     * ★ 중요: 포인트컷 경로가 변경되었습니다.
     * maple.expectation.aop.LogExecutionTime -> maple.expectation.aop.annotation.LogExecutionTime
     */
    @Around("@annotation(maple.expectation.aop.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object proceed = joinPoint.proceed();

        long end = System.currentTimeMillis();
        executionTimes.add(end - start);

        return proceed;
    }

    public List<Long> getAndClearExecutionTimes() {
        List<Long> currentTimes = new ArrayList<>(executionTimes);
        executionTimes.clear();
        return currentTimes;
    }

    public String calculateStatistics(List<Long> times, String testName) {
        if (times.isEmpty()) {
            return String.format("[%s] 실행된 호출이 없습니다.", testName);
        }

        LongStream stream = times.stream().mapToLong(Long::longValue);
        long sum = stream.sum();
        long count = times.size();
        double average = (double) sum / count;
        long max = times.stream().mapToLong(Long::longValue).max().orElse(0L);

        return String.format(
                "🏆 [%s] 통계: 총 호출 수: %d, 총 시간: %dms, 평균 응답 시간: %.2fms, 최대 응답 시간(Latency): %dms",
                testName, count, sum, average, max
        );
    }

    @PreDestroy
    public void printFinalStatistics() {
        String stats = calculateStatistics(executionTimes, "전체 성능 통계");
        log.info("========================================================");
        log.info(stats);
        log.info("========================================================");
    }
}