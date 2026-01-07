package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent; // ✅ 추가
import io.github.resilience4j.core.registry.EntryAddedEvent; // ✅ 추가
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor; // ✅ 주입
import maple.expectation.global.executor.TaskContext; // ✅ 관측성
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
    private final DiscordAlertService discordAlertService;
    private final LogicExecutor executor; // ✅ 지능형 실행기 주입
    private static final String CHANNEL_NAME = "cb-state-sync";

    @PostConstruct
    public void init() {
        //  init 과정 전체를 실행기로 보호하고 중첩 람다 제거
        executor.executeVoid(() -> {
            registry.getAllCircuitBreakers().forEach(this::registerEventListener);
            registry.getEventPublisher().onEntryAdded(this::handleNewCircuitBreaker);
        }, TaskContext.of("CircuitBreaker", "Init"));
    }

    /**
     * 헬퍼 1: 새 서킷 브레이커 추가 이벤트 처리 (람다 평탄화)
     */
    private void handleNewCircuitBreaker(EntryAddedEvent<CircuitBreaker> event) {
        CircuitBreaker addedCb = event.getAddedEntry();
        log.info("🆕 [CB Sync] 새 서킷 브레이커 감지: {}", addedCb.getName());
        registerEventListener(addedCb);
    }

    private void registerEventListener(CircuitBreaker cb) {
        //  상태 전환 리스너의 로직을 별도 메서드로 추출하여 평탄화
        cb.getEventPublisher().onStateTransition(this::handleStateTransition);
    }

    /**
     * 헬퍼 2: 상태 전환 이벤트 처리 (로직 격리)
     */
    private void handleStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String cbName = event.getCircuitBreakerName();
        String toState = event.getStateTransition().getToState().name();
        String fromState = event.getStateTransition().getFromState().name();

        if ("OPEN".equals(toState)) {
            // [패턴 1] executeVoid: Redis 전파 및 알림 과정을 관측 가능한 단위로 실행
            executor.executeVoid(() -> {
                log.warn("📢 [CB Sync] 서킷 열림 감지 -> 전역 전파: {} ({} -> {})", cbName, fromState, toState);
                redisTemplate.convertAndSend(CHANNEL_NAME, cbName + ":" + toState);
                sendDiscordAlertIfCritical(cbName, fromState, toState);
            }, TaskContext.of("CircuitBreaker", "PropagateOpenState", cbName));
        }
    }

    /**
     * ✅  try-catch 제거 및 executeWithRecovery 적용
     */
    private void sendDiscordAlertIfCritical(String cbName, String fromState, String toState) {
        if (!isCriticalService(cbName)) return;

        // [패턴 5] executeWithRecovery: 알림 실패가 시스템 전체에 영향을 주지 않도록 보호
        executor.executeOrCatch(() -> {
            discordAlertService.sendCriticalAlert(
                    buildAlertTitle(cbName),
                    buildAlertDescription(cbName, fromState, toState),
                    new Exception("Circuit breaker state: " + toState)
            );
            log.info("✅ [CB Alert] Discord notification sent: {}", cbName);
            return null;
        }, e -> {
            log.error("❌ [CB Alert] Discord notification failed: {}", e.getMessage());
            return null; // 알림 실패는 정상 시나리오로 복구
        }, TaskContext.of("CircuitBreaker", "SendAlert", cbName));
    }

    public void syncState(String message) {
        executor.executeVoid(() -> {
            String[] parts = message.split(":");
            String cbName = parts[0];
            registry.circuitBreaker(cbName).transitionToOpenState();
            log.warn("🔄 [CB Sync] 전역 신호 수신: {} 서킷 강제 오픈", cbName);
        }, TaskContext.of("CircuitBreaker", "SyncExternalState", message));
    }

    // --- 문자열 생성 로직 분리 (평탄화 보조) ---

    private boolean isCriticalService(String cbName) {
        return "redisLock".equals(cbName) || "nexonApi".equals(cbName);
    }

    private String buildAlertTitle(String cbName) {
        return String.format("🚨 Circuit Breaker OPEN: %s", cbName);
    }

    private String buildAlertDescription(String cbName, String fromState, String toState) {
        String action = "redisLock".equals(cbName)
                ? "Automatic failover to MySQL Named Lock activated"
                : "Service degradation in progress";
        return String.format("Service: %s\nTransition: %s → %s\nAction: %s", cbName, fromState, toState, action);
    }
}