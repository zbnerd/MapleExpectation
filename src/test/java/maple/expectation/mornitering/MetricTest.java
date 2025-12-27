package maple.expectation.mornitering;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.service.v2.LikeProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MetricTest {
    @Autowired
    MeterRegistry registry;
    @Autowired
    LikeProcessor processor;

    @Test
    @DisplayName("좋아요 클릭 시 커스텀 Gauge(global_pending) 수치가 증가해야 한다")
    void like_gauge_increase_test() {
        // given: 새롭게 바뀐 이름 "global_pending"으로 조회
        String metricName = "like.buffer.global_pending";

        // 💡 NPE 방지를 위해 안전하게 조회하는 습관!
        double beforeCount = getGaugeValue(metricName);

        // when
        processor.processLike("UserA");

        // then
        double afterCount = getGaugeValue(metricName);
        assertThat(afterCount).isEqualTo(beforeCount + 1.0);
    }

    private double getGaugeValue(String name) {
        // 💡 registry.find()가 null일 경우를 대비해 0.0을 반환하도록 방어 코딩
        return Optional.ofNullable(registry.find(name).gauge())
                .map(Gauge::value)
                .orElse(0.0);
    }
}