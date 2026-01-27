package maple.expectation.global.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.event.MySQLDownEvent;
import maple.expectation.global.event.MySQLUpEvent;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.service.v2.alert.DiscordAlertService;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * MySQL 상태 이벤트 발행자 (Issue #218)
 *
 * <p>CircuitBreaker(likeSyncDb) 상태 전이를 감지하여 Spring Event로 변환합니다.</p>
 *
 * <h4>Debounce 메커니즘 (P0-2)</h4>
 * <ul>
 *   <li>DOWN 이벤트 수신 후 5초 대기</li>
 *   <li>5초 내 UP 이벤트 발생 시 DOWN 무시 (Flapping 방지)</li>
 *   <li>Redis를 사용하여 Stateless 상태 공유</li>
 * </ul>
 *
 * <h4>상태 키 구조 (P1-2 Hash Tag)</h4>
 * <ul>
 *   <li>{mysql}:state - 현재 상태 (HEALTHY, DEGRADED, RECOVERING)</li>
 *   <li>{mysql}:down:timestamp - DOWN 이벤트 발생 시각</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MySQLHealthEventPublisher {

    private static final String LIKE_SYNC_DB_CB = "likeSyncDb";
    private static final String DOWN_TIMESTAMP_SUFFIX = ":down:timestamp";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redissonClient;
    private final MySQLFallbackProperties properties;
    private final LogicExecutor executor;
    private final DiscordAlertService discordAlertService;
    private final MeterRegistry meterRegistry;

    /**
     * CircuitBreaker 이벤트 리스너 등록 (P0-N1)
     */
    @PostConstruct
    public void registerCircuitBreakerListener() {
        executor.executeVoid(() -> {
            CircuitBreaker likeSyncDbCb = circuitBreakerRegistry.circuitBreaker(LIKE_SYNC_DB_CB);
            likeSyncDbCb.getEventPublisher().onStateTransition(this::handleStateTransition);
            log.info("[MySQLHealth] CircuitBreaker 리스너 등록 완료: {}", LIKE_SYNC_DB_CB);
        }, TaskContext.of("Resilience", "RegisterCBListener", LIKE_SYNC_DB_CB));
    }

    /**
     * CircuitBreaker 상태 전이 처리
     */
    private void handleStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        String fromState = event.getStateTransition().getFromState().name();
        String toState = event.getStateTransition().getToState().name();

        log.info("[MySQLHealth] CB 상태 전이 감지: {} → {}", fromState, toState);

        if ("OPEN".equals(toState)) {
            handleCircuitBreakerOpen(fromState, toState);
        } else if ("CLOSED".equals(toState)) {
            handleCircuitBreakerClosed(fromState, toState);
        }
    }

    /**
     * CircuitBreaker OPEN 처리 (MySQL DOWN 감지)
     *
     * <p>Debounce를 위해 DOWN 타임스탬프를 Redis에 저장하고,
     * 비동기로 5초 후 실제 DOWN 이벤트를 발행합니다.</p>
     */
    private void handleCircuitBreakerOpen(String fromState, String toState) {
        executor.executeVoid(() -> {
            MySQLHealthState currentState = getCurrentState();

            // 이미 DEGRADED 상태면 무시
            if (currentState.isDegraded()) {
                log.debug("[MySQLHealth] 이미 DEGRADED 상태, DOWN 이벤트 무시");
                incrementFlappingIgnored();
                return;
            }

            // DOWN 타임스탬프 저장 (Debounce용)
            saveDownTimestamp();

            // 비동기로 Debounce 후 이벤트 발행
            scheduleDownEventAfterDebounce(fromState, toState);

        }, TaskContext.of("Resilience", "HandleCBOpen", LIKE_SYNC_DB_CB));
    }

    /**
     * Debounce 후 DOWN 이벤트 발행 (P0-N4: @Async)
     */
    @Async
    public void scheduleDownEventAfterDebounce(String fromState, String toState) {
        executor.executeVoid(() -> {
            // Debounce 대기
            TimeUnit.SECONDS.sleep(properties.getDebounceSeconds());

            // 대기 후 UP 이벤트가 발생했는지 확인
            if (!isDownTimestampValid()) {
                log.info("[MySQLHealth] Debounce 중 UP 이벤트 발생 - DOWN 무시 (Flapping)");
                incrementFlappingIgnored();
                return;
            }

            // 상태 전이 및 이벤트 발행
            updateState(MySQLHealthState.DEGRADED);
            clearDownTimestamp();

            MySQLDownEvent event = MySQLDownEvent.of(LIKE_SYNC_DB_CB, fromState, toState);
            eventPublisher.publishEvent(event);

            log.warn("[MySQLHealth] MySQL DOWN 이벤트 발행: {} → {}", fromState, toState);
            incrementStateTransition();
            sendDiscordAlert("MySQL DOWN", fromState, toState);

        }, TaskContext.of("Resilience", "PublishDownEvent", LIKE_SYNC_DB_CB));
    }

    /**
     * CircuitBreaker CLOSED 처리 (MySQL UP 감지)
     */
    private void handleCircuitBreakerClosed(String fromState, String toState) {
        executor.executeVoid(() -> {
            MySQLHealthState currentState = getCurrentState();

            // DOWN 타임스탬프 제거 (Debounce 취소)
            clearDownTimestamp();

            // HEALTHY 상태에서 CLOSED면 무시
            if (currentState.isHealthy()) {
                log.debug("[MySQLHealth] 이미 HEALTHY 상태, UP 이벤트 무시");
                return;
            }

            // DEGRADED → RECOVERING 전이
            MySQLHealthState newState = currentState.onCircuitBreakerClosed();
            updateState(newState);

            MySQLUpEvent event = MySQLUpEvent.of(LIKE_SYNC_DB_CB, fromState, toState);
            eventPublisher.publishEvent(event);

            log.info("[MySQLHealth] MySQL UP 이벤트 발행: {} → {}", fromState, toState);
            incrementStateTransition();
            sendDiscordAlert("MySQL RECOVERING", fromState, toState);

        }, TaskContext.of("Resilience", "PublishUpEvent", LIKE_SYNC_DB_CB));
    }

    /**
     * 복구 완료 처리 (RECOVERING → HEALTHY)
     *
     * <p>CompensationSyncScheduler에서 동기화 완료 후 호출합니다.</p>
     */
    public void markRecoveryComplete() {
        executor.executeVoid(() -> {
            MySQLHealthState currentState = getCurrentState();
            if (currentState != MySQLHealthState.RECOVERING) {
                log.debug("[MySQLHealth] RECOVERING 상태가 아님, 복구 완료 무시: {}", currentState);
                return;
            }

            MySQLHealthState newState = currentState.onRecoveryComplete();
            updateState(newState);
            log.info("[MySQLHealth] 복구 완료: RECOVERING → HEALTHY");
            incrementStateTransition();

        }, TaskContext.of("Resilience", "MarkRecoveryComplete", LIKE_SYNC_DB_CB));
    }

    // ==================== Redis 상태 관리 ====================

    /**
     * 현재 MySQL 상태 조회 (Stateless)
     */
    public MySQLHealthState getCurrentState() {
        return executor.executeOrDefault(() -> {
            RBucket<String> bucket = redissonClient.getBucket(properties.getStateKey());
            String stateStr = bucket.get();
            return stateStr != null ? MySQLHealthState.valueOf(stateStr) : MySQLHealthState.HEALTHY;
        }, MySQLHealthState.HEALTHY, TaskContext.of("Resilience", "GetState", properties.getStateKey()));
    }

    /**
     * MySQL 상태 업데이트 (P1-N5: TTL 적용)
     */
    private void updateState(MySQLHealthState state) {
        RBucket<String> bucket = redissonClient.getBucket(properties.getStateKey());
        bucket.set(state.name(), Duration.ofSeconds(properties.getStateTtlSeconds()));
        log.debug("[MySQLHealth] 상태 업데이트: {} (TTL: {}s)", state, properties.getStateTtlSeconds());
    }

    /**
     * DOWN 타임스탬프 저장
     */
    private void saveDownTimestamp() {
        String key = properties.getStateKey() + DOWN_TIMESTAMP_SUFFIX;
        RBucket<Long> bucket = redissonClient.getBucket(key);
        bucket.set(Instant.now().toEpochMilli(), Duration.ofSeconds(properties.getStateTtlSeconds()));
    }

    /**
     * DOWN 타임스탬프 유효성 확인
     *
     * <p>UP 이벤트가 발생하면 타임스탬프가 삭제되므로, 존재 여부로 판단합니다.</p>
     */
    private boolean isDownTimestampValid() {
        String key = properties.getStateKey() + DOWN_TIMESTAMP_SUFFIX;
        RBucket<Long> bucket = redissonClient.getBucket(key);
        return bucket.isExists();
    }

    /**
     * DOWN 타임스탬프 삭제
     */
    private void clearDownTimestamp() {
        String key = properties.getStateKey() + DOWN_TIMESTAMP_SUFFIX;
        redissonClient.getBucket(key).delete();
    }

    // ==================== 메트릭 및 알림 ====================

    private void incrementStateTransition() {
        meterRegistry.counter("mysql.state.transition").increment();
    }

    private void incrementFlappingIgnored() {
        meterRegistry.counter("mysql.flapping.ignored").increment();
    }

    /**
     * Discord 알림 발송 (P1-N6: best-effort)
     */
    private void sendDiscordAlert(String event, String fromState, String toState) {
        executor.executeOrDefault(() -> {
            String title = String.format("🚨 %s 감지", event);
            String description = String.format(
                    "CircuitBreaker: %s\nTransition: %s → %s\nTimestamp: %s",
                    LIKE_SYNC_DB_CB, fromState, toState, Instant.now()
            );
            discordAlertService.sendCriticalAlert(title, description, new Exception(event));
            return null;
        }, null, TaskContext.of("Resilience", "SendAlert", event));
    }
}
