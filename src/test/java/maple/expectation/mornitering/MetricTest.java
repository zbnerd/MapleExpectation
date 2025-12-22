package maple.expectation.mornitering;

import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.service.v2.LikeProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MetricTest {
    @Autowired
    MeterRegistry registry;
    @Autowired
    LikeProcessor processor;

    @Test
    @DisplayName("좋아요 클릭 시 커스텀 Gauge(total_pending) 수치가 증가해야 한다")
    void like_gauge_increase_test() {
        // given: 현재 수치 확인
        double beforeCount = getGaugeValue("like.buffer.total_pending");

        // when: 좋아요 처리
        processor.processLike("UserA");

        // then: 수치 1 증가 확인
        double afterCount = getGaugeValue("like.buffer.total_pending");
        assertThat(afterCount).isEqualTo(beforeCount + 1.0);
    }

    private double getGaugeValue(String name) {
        return registry.find(name).gauge().value();
    }
}