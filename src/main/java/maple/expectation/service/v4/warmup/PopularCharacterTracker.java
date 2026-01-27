package maple.expectation.service.v4.warmup;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * 인기 캐릭터 호출 횟수 트래커 (#275 Auto Warmup)
 *
 * <h3>기능</h3>
 * <p>V4 API 호출 시 캐릭터별 호출 횟수를 Redis Sorted Set에 기록합니다.
 * 이 데이터를 기반으로 인기 캐릭터를 자동 웜업하여 캐시 히트율을 높입니다.</p>
 *
 * <h3>Redis 구조</h3>
 * <pre>
 * Key:   popular:characters:{yyyy-MM-dd}
 * Type:  Sorted Set (ZSET)
 * Score: 호출 횟수
 * Member: userIgn (캐릭터 닉네임)
 * TTL:   48시간 (전날 데이터 참조용)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Green (Performance): ZINCRBY O(log N) - 높은 성능</li>
 *   <li>Blue (Architect): 일별 키로 데이터 분리, TTL 자동 만료</li>
 *   <li>Red (SRE): 비동기 기록으로 API 응답 지연 방지</li>
 *   <li>Purple (Auditor): 로그 및 메트릭으로 추적 가능</li>
 * </ul>
 *
 * @see PopularCharacterWarmupScheduler 웜업 스케줄러
 */
@Slf4j
@Component
public class PopularCharacterTracker {

    private static final String KEY_PREFIX = "popular:characters:";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Duration KEY_TTL = Duration.ofHours(48);  // 전날 데이터 참조용

    private final RedissonClient redissonClient;
    private final LogicExecutor executor;
    private final MeterRegistry meterRegistry;

    public PopularCharacterTracker(
            RedissonClient redissonClient,
            LogicExecutor executor,
            MeterRegistry meterRegistry) {
        this.redissonClient = redissonClient;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 캐릭터 호출 기록 (Fire-and-Forget)
     *
     * <p>API 응답 지연을 방지하기 위해 실패해도 예외를 던지지 않습니다.</p>
     *
     * @param userIgn 캐릭터 닉네임
     */
    public void recordAccess(String userIgn) {
        executor.executeOrDefault(
                () -> {
                    String key = buildKey(LocalDate.now());
                    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

                    // ZINCRBY: 호출 횟수 증가
                    double newScore = zset.addScore(userIgn, 1);

                    // 첫 호출 시 TTL 설정 (키가 새로 생성된 경우)
                    if (newScore == 1.0 && zset.remainTimeToLive() < 0) {
                        zset.expire(KEY_TTL);
                    }

                    meterRegistry.counter("warmup.tracker.record", "status", "success").increment();
                    log.debug("[PopularTracker] Recorded access: {} (count={})", userIgn, (int) newScore);
                    return null;
                },
                null,
                TaskContext.of("PopularTracker", "RecordAccess", userIgn)
        );
    }

    /**
     * 인기 캐릭터 조회 (상위 N개)
     *
     * @param date 조회 날짜
     * @param limit 상위 N개
     * @return 인기 캐릭터 닉네임 목록 (호출 횟수 내림차순)
     */
    public List<String> getTopCharacters(LocalDate date, int limit) {
        return executor.executeOrDefault(
                () -> {
                    String key = buildKey(date);
                    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

                    // ZREVRANGE: 높은 점수 순으로 조회
                    Collection<String> top = zset.valueRangeReversed(0, limit - 1);

                    log.info("[PopularTracker] Top {} characters for {}: {}", limit, date, top.size());
                    return List.copyOf(top);
                },
                List.of(),
                TaskContext.of("PopularTracker", "GetTop", String.valueOf(limit))
        );
    }

    /**
     * 전날 인기 캐릭터 조회 (웜업용)
     *
     * @param limit 상위 N개
     * @return 전날 인기 캐릭터 목록
     */
    public List<String> getYesterdayTopCharacters(int limit) {
        return getTopCharacters(LocalDate.now().minusDays(1), limit);
    }

    /**
     * 캐릭터별 호출 횟수 조회
     *
     * @param date 조회 날짜
     * @param userIgn 캐릭터 닉네임
     * @return 호출 횟수 (없으면 0)
     */
    public int getAccessCount(LocalDate date, String userIgn) {
        return executor.executeOrDefault(
                () -> {
                    String key = buildKey(date);
                    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);

                    Double score = zset.getScore(userIgn);
                    return score != null ? score.intValue() : 0;
                },
                0,
                TaskContext.of("PopularTracker", "GetCount", userIgn)
        );
    }

    /**
     * 오늘 총 유니크 캐릭터 수 조회 (메트릭용)
     *
     * @return 유니크 캐릭터 수
     */
    public int getTodayUniqueCount() {
        return executor.executeOrDefault(
                () -> {
                    String key = buildKey(LocalDate.now());
                    RScoredSortedSet<String> zset = redissonClient.getScoredSortedSet(key);
                    return zset.size();
                },
                0,
                TaskContext.of("PopularTracker", "GetUniqueCount")
        );
    }

    /**
     * Redis 키 생성
     *
     * @param date 날짜
     * @return Redis 키
     */
    private String buildKey(LocalDate date) {
        return KEY_PREFIX + date.format(DATE_FORMATTER);
    }
}
