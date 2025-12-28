package maple.expectation.aop.collector;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class PerformanceStatisticsCollector {

    private final MeterRegistry registry; // ✅ 스프링 표준 메트릭 저장소

    /**
     * ✅ JVM 내부 필드를 삭제하고 Micrometer Timer로 대체
     * Timer는 내부적으로 count, sum, max를 모두 관리합니다.
     */
    public void addTime(String testName, long time) {
        Timer.builder("nexon.api.performance") // 메트릭 이름
                .tag("service", testName)        // 태그를 통해 인스턴스별/API별 구분 가능
                .description("Nexon API 호출 성능 통계")
                .register(registry)
                .record(time, TimeUnit.MILLISECONDS);
    }

    /**
     * ✅ Micrometer에서 집계된 데이터를 가져와 출력용 데이터로 변환
     */
    public String[] calculateStatistics(String testName) {
        Timer timer = registry.find("nexon.api.performance")
                .tag("service", testName)
                .timer();

        if (timer == null) {
            return new String[]{"🏆 [" + testName + "] 수집된 통계 데이터가 없습니다."};
        }

        long count = timer.count();
        double totalTime = timer.totalTime(TimeUnit.MILLISECONDS);
        double maxTime = timer.max(TimeUnit.MILLISECONDS);
        double average = timer.mean(TimeUnit.MILLISECONDS);

        return new String[]{
                String.format("🏆 [%s] 전역 성능 통계 (Micrometer):", testName),
                String.format("- 총 호출 수: %d회", count),
                String.format("- 총 소요 시간: %.0fms", totalTime),
                String.format("- 평균 응답 시간: %.2fms", average),
                String.format("- 최대 Latency: %.0fms", maxTime)
        };
    }
}