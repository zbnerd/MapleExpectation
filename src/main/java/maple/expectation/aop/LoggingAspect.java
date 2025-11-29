package maple.expectation.aop;

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

    // ⭐️ 모든 스레드가 동시에 접근 가능한 스레드 세이프(Thread-safe)한 리스트
    private final List<Long> executionTimes = Collections.synchronizedList(new ArrayList<>());

    // ⭐️ 통계를 담을 Map (테스트별로 분리하기 위해)
    private final java.util.Map<String, List<Long>> testExecutionTimes = new java.util.HashMap<>();

    /**
     * @Around: @LogExecutionTime 어노테이션이 붙은 모든 메서드의 실행을 감싸서 실행합니다.
     * ProceedingJoinPoint를 통해 원본 메서드를 실행하고 그 전후로 로직을 삽입합니다.
     */

    @Around("@annotation(maple.expectation.aop.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis(); // 1. 측정시작시간

        // 실제 biz 로직실행 원본메서드 호출
        Object proceed = joinPoint.proceed();

        long end = System.currentTimeMillis(); // 3. 측정종료시간

        String methodName = joinPoint.getSignature().toShortString();
//        log.info("📊 [AOP TIME CHECK] {} 실행 완료. 소요 시간: {}ms", methodName, end-start);

        // 2. 실행 시간을 리스트에 추가 (멀티스레드 환경이므로 synchronizedList 사용)
        executionTimes.add(end-start);

        return proceed; // 결과 호출지점으로 반환
    }

    public List<Long> getAndClearExecutionTimes() {
        // 현재까지 기록된 리스트를 가져온 후 초기화합니다.
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

}
