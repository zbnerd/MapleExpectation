package maple.expectation.monitoring.throttle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 알림 스로틀러 (Issue #251) - Redis 기반 분산 상태 관리
 *
 * <h3>[P0-Green] 비용 제한 메커니즘</h3>
 * <ul>
 *   <li>일일 LLM 호출 한도 제한 (Redis RAtomicLong)</li>
 *   <li>동일 에러 패턴 스로틀링 (Redis RMap)</li>
 *   <li>Rate Limiting (Decorator 패턴)</li>
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 * <ul>
 *   <li>Section 6 (Design Patterns): Decorator 패턴, Constructor Injection</li>
 *   <li>Section 12 (LogicExecutor): executeOrDefault 패턴</li>
 *   <li>Section 8-1 (Redis): Hash Tag ({ai-throttle}) for Cluster</li>
 * </ul>
 *
 * <h4>Redis 키 구조</h4>
 * <ul>
 *   <li>일일 카운터: {ai-throttle}:daily-count:2026-02-01 (TTL 25시간)</li>
 *   <li>패턴 타임스탬프: {ai-throttle}:pattern-times (TTL 10배 스로틀링)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.sre.enabled", havingValue = "true")
public class AlertThrottler {

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;

    @Value("${ai.sre.daily-limit:100}")
    private int dailyLimit;

    @Value("${ai.sre.throttle-seconds:60}")
    private int throttleSeconds;

    /**
     * Redis 키 생성 헬퍼
     */
    private String buildDailyCountKey() {
        return "{ai-throttle}:daily-count:" + LocalDate.now();
    }

    private String buildPatternTimesKey() {
        return "{ai-throttle}:pattern-times";
    }

    /**
     * 일일 카운터 Redis 객체 조회
     */
    private RAtomicLong getDailyCounter() {
        RAtomicLong counter = redissonClient.getAtomicLong(buildDailyCountKey());
        // 25시간 TTL 설정 (자정 넘어가도 안전하게 만료)
        executor.executeVoid(
                () -> this.setCounterExpiry(counter),
                TaskContext.of("AlertThrottler", "SetDailyTTL")
        );
        return counter;
    }

    private void setCounterExpiry(RAtomicLong counter) {
        if (!counter.isExists() || counter.remainTimeToLive() < 0) {
            counter.expire(25, TimeUnit.HOURS);
        }
    }

    /**
     * 패턴별 타임스탬프 Redis Map 조회
     */
    private RMap<String, Long> getPatternTimesMap() {
        RMap<String, Long> map = redissonClient.getMap(buildPatternTimesKey());
        // TTL: 스로틀링 시간의 10배
        executor.executeVoid(
                () -> this.setMapExpiry(map),
                TaskContext.of("AlertThrottler", "SetPatternMapTTL")
        );
        return map;
    }

    private void setMapExpiry(RMap<String, Long> map) {
        if (!map.isExists() || map.remainTimeToLive() < 0) {
            map.expire(throttleSeconds * 10L, TimeUnit.SECONDS);
        }
    }

    /**
     * 매일 자정에 일일 카운터 리셋 (Redis TTL 기반 자동 만료)
     * Redis 키는 TTL로 자동 삭제되므로 로그 출력만 수행
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyCount() {
        log.info("[AlertThrottler] 일일 AI 호출 카운터 리셋 (Redis TTL 기반 자동 만료)");
    }

    /**
     * AI 분석 호출 가능 여부 확인 (Redis 분산 상태)
     *
     * @return 호출 가능하면 true
     */
    public boolean canSendAiAnalysis() {
        return executor.executeOrDefault(
                this::checkAndIncrementDailyCount,
                false,
                TaskContext.of("AlertThrottler", "CanSendAI")
        );
    }

    private boolean checkAndIncrementDailyCount() {
        RAtomicLong counter = getDailyCounter();
        long current = counter.incrementAndGet();

        if (current > dailyLimit) {
            log.warn("[AlertThrottler] 일일 AI 호출 한도 초과: {}/{}", current, dailyLimit);
            counter.decrementAndGet(); // 롤백
            return false;
        }
        return true;
    }

    /**
     * 동일 에러 패턴 스로틀링 확인 (Redis 분산 상태)
     *
     * @param errorPattern 에러 패턴 (예: 예외 클래스명)
     * @return 전송 가능하면 true
     */
    public boolean shouldSendAlert(String errorPattern) {
        return executor.executeOrDefault(
                () -> this.checkPatternThrottle(errorPattern),
                false,
                TaskContext.of("AlertThrottler", "ShouldSend", errorPattern)
        );
    }

    private boolean checkPatternThrottle(String errorPattern) {
        long nowEpochSeconds = Instant.now().getEpochSecond();
        RMap<String, Long> patternTimes = getPatternTimesMap();

        return Optional.ofNullable(patternTimes.get(errorPattern))
                .map(lastTime -> this.evaluateThrottle(errorPattern, lastTime, nowEpochSeconds, patternTimes))
                .orElseGet(() -> this.allowAndRecord(errorPattern, nowEpochSeconds, patternTimes));
    }

    private boolean evaluateThrottle(String pattern, long lastTime, long now, RMap<String, Long> map) {
        long elapsed = now - lastTime;
        if (elapsed < throttleSeconds) {
            log.debug("[AlertThrottler] 스로틀링: {} ({}초 후 재전송 가능)", pattern, throttleSeconds - elapsed);
            return false;
        }
        return allowAndRecord(pattern, now, map);
    }

    private boolean allowAndRecord(String pattern, long now, RMap<String, Long> map) {
        map.put(pattern, now);
        return true;
    }

    /**
     * AI 분석과 스로틀링 동시 확인
     *
     * @param errorPattern 에러 패턴
     * @return 전송 가능하면 true
     */
    public boolean canSendAiAnalysisWithThrottle(String errorPattern) {
        return shouldSendAlert(errorPattern) && canSendAiAnalysis();
    }

    /**
     * 현재 일일 사용량 조회 (Redis 기반)
     *
     * @return 오늘 사용된 AI 호출 수
     */
    public int getDailyUsage() {
        return executor.executeOrDefault(
                this::fetchDailyUsage,
                0,
                TaskContext.of("AlertThrottler", "GetUsage")
        );
    }

    private int fetchDailyUsage() {
        return (int) getDailyCounter().get();
    }

    /**
     * 남은 일일 호출 수 조회
     *
     * @return 남은 호출 가능 수
     */
    public int getRemainingCalls() {
        return Math.max(0, dailyLimit - getDailyUsage());
    }

    /**
     * 스로틀 캐시 정리 (Redis TTL 기반 자동 만료)
     * Redis Map은 TTL로 자동 삭제되므로 명시적 정리 불필요
     */
    @Scheduled(fixedRate = 3600000) // 1시간마다
    public void cleanupThrottleCache() {
        log.debug("[AlertThrottler] 스로틀 캐시는 Redis TTL로 자동 관리됨 ({}초)", throttleSeconds * 10);
    }
}
