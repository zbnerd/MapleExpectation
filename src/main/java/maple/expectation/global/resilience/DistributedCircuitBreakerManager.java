package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedCircuitBreakerManager {

    private final CircuitBreakerRegistry registry;
    private final StringRedisTemplate redisTemplate;
    private static final String CHANNEL_NAME = "cb-state-sync";

    @PostConstruct
    public void init() {
        // 1. 기존에 이미 생성된 서킷 브레이커들에 리스너 등록
        registry.getAllCircuitBreakers().forEach(this::registerEventListener);

        // ✅ 2. 핵심 수정: 향후 "새롭게 생성되는" 서킷 브레이커에도 자동으로 리스너 등록
        registry.getEventPublisher().onEntryAdded(event -> {
            CircuitBreaker addedCb = event.getAddedEntry();
            log.info("🆕 [CB Sync] 새 서킷 브레이커 감지 및 리스너 등록: {}", addedCb.getName());
            registerEventListener(addedCb);
        });
    }

    private void registerEventListener(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event -> {
            String state = event.getStateTransition().getToState().name();
            // 상태가 OPEN으로 변할 때만 Redis 전파
            if ("OPEN".equals(state)) {
                log.info("📢 [CB Sync] 서킷 열림 감지 -> 전역 전파: {}", cb.getName());
                redisTemplate.convertAndSend(CHANNEL_NAME, cb.getName() + ":" + state);
            }
        });
    }

    public void syncState(String message) {
        String[] parts = message.split(":");
        String cbName = parts[0];
        // 수신 시 로컬 서킷 강제 오픈
        registry.circuitBreaker(cbName).transitionToOpenState();
        log.warn("🔄 [CB Sync] 전역 신호 수신: {} 서킷 강제 오픈", cbName);
    }
}