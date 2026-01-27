package maple.expectation.monitoring.throttle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 알림 스로틀러 (Issue #251)
 *
 * <h3>[P0-Green] 비용 제한 메커니즘</h3>
 * <ul>
 *   <li>일일 LLM 호출 한도 제한</li>
 *   <li>동일 에러 패턴 스로틀링</li>
 *   <li>Rate Limiting (Decorator 패턴)</li>
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 * <ul>
 *   <li>Section 6 (Design Patterns): Decorator 패턴</li>
 *   <li>Section 12 (LogicExecutor): 상태 관리 Stateless</li>
 * </ul>
 */
@Slf4j
@Component
public class AlertThrottler {

    private final AtomicInteger dailyAiCallCount = new AtomicInteger(0);
    private final Map<String, Instant> lastAlertTimeByPattern = new ConcurrentHashMap<>();

    @Value("${ai.sre.daily-limit:100}")
    private int dailyLimit;

    @Value("${ai.sre.throttle-seconds:60}")
    private int throttleSeconds;

    /**
     * 매일 자정에 일일 카운터 리셋
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyCount() {
        int previousCount = dailyAiCallCount.getAndSet(0);
        log.info("[AlertThrottler] 일일 AI 호출 카운터 리셋: {} -> 0", previousCount);
    }

    /**
     * AI 분석 호출 가능 여부 확인
     *
     * @return 호출 가능하면 true
     */
    public boolean canSendAiAnalysis() {
        int current = dailyAiCallCount.incrementAndGet();
        if (current > dailyLimit) {
            log.warn("[AlertThrottler] 일일 AI 호출 한도 초과: {}/{}", current, dailyLimit);
            dailyAiCallCount.decrementAndGet(); // 롤백
            return false;
        }
        return true;
    }

    /**
     * 동일 에러 패턴 스로틀링 확인
     *
     * @param errorPattern 에러 패턴 (예: 예외 클래스명)
     * @return 전송 가능하면 true
     */
    public boolean shouldSendAlert(String errorPattern) {
        Instant now = Instant.now();
        Instant lastTime = lastAlertTimeByPattern.get(errorPattern);

        if (lastTime != null) {
            long secondsSinceLast = now.getEpochSecond() - lastTime.getEpochSecond();
            if (secondsSinceLast < throttleSeconds) {
                log.debug("[AlertThrottler] 스로틀링: {} ({}초 후 재전송 가능)",
                        errorPattern, throttleSeconds - secondsSinceLast);
                return false;
            }
        }

        lastAlertTimeByPattern.put(errorPattern, now);
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
     * 현재 일일 사용량 조회
     *
     * @return 오늘 사용된 AI 호출 수
     */
    public int getDailyUsage() {
        return dailyAiCallCount.get();
    }

    /**
     * 남은 일일 호출 수 조회
     *
     * @return 남은 호출 가능 수
     */
    public int getRemainingCalls() {
        return Math.max(0, dailyLimit - dailyAiCallCount.get());
    }

    /**
     * 스로틀 캐시 정리 (오래된 항목 제거)
     */
    @Scheduled(fixedRate = 3600000) // 1시간마다
    public void cleanupThrottleCache() {
        Instant cutoff = Instant.now().minusSeconds(throttleSeconds * 10L);
        lastAlertTimeByPattern.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        log.debug("[AlertThrottler] 스로틀 캐시 정리 완료. 현재 항목 수: {}", lastAlertTimeByPattern.size());
    }
}
