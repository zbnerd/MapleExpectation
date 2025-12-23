package maple.expectation.aop.collector;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Component
public class PerformanceStatisticsCollector {

    // 💡 메모리 누수 방지: 큐를 버리고 누적 합산 필드(상수 메모리) 사용
    private final LongAdder totalTimeAdder = new LongAdder();
    private final LongAdder countAdder = new LongAdder();
    private final AtomicLong maxTime = new AtomicLong(0);

    public void addTime(long time) {
        totalTimeAdder.add(time);
        countAdder.increment();
        // 💡 최대 응답 시간 갱신 (스레드 안전)
        maxTime.updateAndGet(currentMax -> Math.max(currentMax, time));
    }

    public void reset() {
        totalTimeAdder.reset();
        countAdder.reset();
        maxTime.set(0);
    }

    public String[] calculateStatistics(String testName) {
        long count = countAdder.sum();
        long sum = totalTimeAdder.sum();
        long max = maxTime.get();
        double average = (count == 0) ? 0 : (double) sum / count;

        return new String[]{
                String.format("🏆 [%s] 성능 통계:", testName),
                String.format("- 총 호출 수: %d회", count),
                String.format("- 총 소요 시간: %dms", sum),
                String.format("- 평균 응답 시간: %.2fms", average),
                String.format("- 최대 Latency: %dms", max)
        };
    }
}