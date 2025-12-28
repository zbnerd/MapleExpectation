package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import maple.expectation.mornitering.MonitoringAlertService;
import maple.expectation.repository.v2.RedisBufferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class DistributedCircuitBreakerTest {

    @Autowired
    private CircuitBreakerRegistry registry;

    @Autowired
    private DistributedCircuitBreakerManager manager;

    @MockitoBean
    private StringRedisTemplate redisTemplate; // 발행 여부 확인용

    // 💡 핵심: 테스트와 직접 관계없는 빈들을 Mock으로 대체하여 컨텍스트 로딩 에러를 방지합니다.
    @MockitoBean
    private MonitoringAlertService monitoringAlertService;

    @MockitoBean
    private RedisBufferRepository redisBufferRepository;

    @Test
    @DisplayName("로컬 서킷이 OPEN으로 변하면 Redis 채널로 상태 변경 메시지를 발행한다")
    void localOpen_ShouldPublishToRedis() {
        // Given
        CircuitBreaker cb = registry.circuitBreaker("nexonApiClient");
        cb.transitionToClosedState(); // ✅ 확실하게 CLOSED 상태에서 시작

        // When: 서킷 상태를 강제로 OPEN으로 변경 (이때 이벤트가 터져야 함)
        cb.transitionToOpenState();

        // Then: 리스너가 동작하여 Redis로 메시지를 보냈는지 확인
        verify(redisTemplate, times(1))
                .convertAndSend(eq("cb-state-sync"), eq("nexonApiClient:OPEN"));
    }

    @Test
    @DisplayName("Redis 메시지를 수신하면 해당 서킷의 상태를 OPEN으로 강제 전환한다")
    void receivedRedisMessage_ShouldTransitionToOpen() {
        // Given
        String cbName = "nexonApiClient";
        CircuitBreaker cb = registry.circuitBreaker(cbName);
        cb.transitionToClosedState(); // 초기 상태: CLOSED

        // When: 매니저의 syncState를 직접 호출 (Redis 메시지 수신 상황 시뮬레이션)
        manager.syncState(cbName + ":OPEN");

        // Then: 로컬 서킷 상태가 OPEN으로 변했는지 확인
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}