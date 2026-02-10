package maple.expectation.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@DisplayName("분산 서킷 브레이커 상태 동기화 테스트")
class DistributedCircuitBreakerTest extends IntegrationTestSupport {

  @Autowired private CircuitBreakerRegistry registry;
  @Autowired private DistributedCircuitBreakerManager manager;

  // 💡 실제 빈의 동작을 관찰하기 위해 Mock 대신 Spy 사용 가능
  @MockitoSpyBean private StringRedisTemplate redisTemplate;

  @Test
  @DisplayName("로컬 서킷이 OPEN으로 변하면 Redis 채널로 상태 변경 메시지를 발행한다")
  void localOpen_ShouldPublishToRedis() {
    CircuitBreaker cb = registry.circuitBreaker("nexonApiClient");
    cb.transitionToClosedState();

    cb.transitionToOpenState();

    verify(redisTemplate, atLeastOnce())
        .convertAndSend(eq("cb-state-sync"), eq("nexonApiClient:OPEN"));
  }

  @Test
  @DisplayName("Redis 메시지를 수신하면 해당 서킷의 상태를 OPEN으로 강제 전환한다")
  void receivedRedisMessage_ShouldTransitionToOpen() {
    String cbName = "nexonApiClient";
    CircuitBreaker cb = registry.circuitBreaker(cbName);
    cb.transitionToClosedState();

    manager.syncState(cbName + ":OPEN");

    assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }
}
