package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedCircuitBreakerManager {

    private final CircuitBreakerRegistry registry;
    private final StringRedisTemplate redisTemplate;
    private final DiscordAlertService discordAlertService;  // ✅ Discord 알림 서비스 추가
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
            String fromState = event.getStateTransition().getFromState().name();
            String toState = event.getStateTransition().getToState().name();

            // 상태가 OPEN으로 변할 때만 Redis 전파
            if ("OPEN".equals(toState)) {
                log.warn("📢 [CB Sync] 서킷 열림 감지 -> 전역 전파: {} ({} -> {})",
                    cb.getName(), fromState, toState);
                redisTemplate.convertAndSend(CHANNEL_NAME, cb.getName() + ":" + toState);

                // ✅ 중요 서비스에 대해 Discord 알림 발송
                sendDiscordAlertIfCritical(cb.getName(), fromState, toState);
            }
        });
    }

    /**
     * 중요 Circuit Breaker에 대해 Discord 알림 발송
     *
     * @param cbName    Circuit Breaker 이름
     * @param fromState 이전 상태
     * @param toState   변경된 상태
     */
    private void sendDiscordAlertIfCritical(String cbName, String fromState, String toState) {
        // redisLock과 nexonApi는 중요 서비스로 간주
        if ("redisLock".equals(cbName) || "nexonApi".equals(cbName)) {
            String title = String.format("🚨 Circuit Breaker OPEN: %s", cbName);
            String description = String.format(
                "Circuit breaker state transition detected:\n" +
                "- Service: %s\n" +
                "- Transition: %s → %s\n" +
                "- Action: %s",
                cbName,
                fromState,
                toState,
                "redisLock".equals(cbName) ? "Automatic failover to MySQL Named Lock activated" : "Service degradation in progress"
            );

            try {
                discordAlertService.sendCriticalAlert(
                    title,
                    description,
                    new Exception("Circuit breaker state: " + toState)
                );
                log.info("✅ [CB Alert] Discord notification sent for: {}", cbName);
            } catch (Exception e) {
                log.error("❌ [CB Alert] Failed to send Discord notification: {}", e.getMessage());
                // Discord 알림 실패는 시스템에 영향 주지 않음 (로그만 남김)
            }
        }
    }

    public void syncState(String message) {
        String[] parts = message.split(":");
        String cbName = parts[0];
        // 수신 시 로컬 서킷 강제 오픈
        registry.circuitBreaker(cbName).transitionToOpenState();
        log.warn("🔄 [CB Sync] 전역 신호 수신: {} 서킷 강제 오픈", cbName);
    }
}