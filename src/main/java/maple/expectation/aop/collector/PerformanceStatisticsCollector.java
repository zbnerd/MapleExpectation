package maple.expectation.aop.collector;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class PerformanceStatisticsCollector {

    // ConcurrentLinkedQueue는 여러 쓰레드가 동시에 데이터를 넣어도 락 없이 안전하게 동작합니다.
    private final ConcurrentLinkedQueue<Long> executionTimes = new ConcurrentLinkedQueue<>();

    public void addTime(long time) {
        executionTimes.offer(time);
    }

    public List<Long> getAndClear() {
        List<Long> result = new ArrayList<>();
        Long time;
        // 큐에서 하나씩 꺼내어 리스트로 복사하고 큐를 비웁니다.
        while ((time = executionTimes.poll()) != null) {
            result.add(time);
        }
        return result;
    }

    public String[] calculateStatistics(List<Long> times, String testName) {
        if (times.isEmpty()) {
            return new String[]{String.format("[%s] 실행된 호출이 없습니다.", testName)};
        }

        long sum = times.stream().mapToLong(Long::longValue).sum();
        long count = times.size();
        double average = (double) sum / count;
        long max = times.stream().mapToLong(Long::longValue).max().orElse(0L);

        return new String[]{
            String.format("🏆 [%s] 통계:", testName),
            String.format("총 호출 수: %d", count),
            String.format("총 시간: %dms", sum),
            String.format("평균 응답 시간: %.2fms", average),
            String.format("최대 응답 시간(Latency): %dms", max)
        };
    }
}