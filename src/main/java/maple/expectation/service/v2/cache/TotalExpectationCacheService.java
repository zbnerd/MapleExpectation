package maple.expectation.service.v2.cache;

import lombok.extern.slf4j.Slf4j;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * TotalExpectationResponse 전용 캐시 서비스 (Issue #158)
 *
 * <h4>P0-2 정책 (L2 장애 시에도 응답 정상 반환)</h4>
 * <ul>
 *   <li>L1: 가능한 한 저장 (캐시 누락 시 warn)</li>
 *   <li>L2: 5KB 초과면 저장 스킵</li>
 *   <li>L2 put 실패해도 API 실패로 전파 금지 (로그+메트릭)</li>
 * </ul>
 *
 * <h4>불변식 1: 5KB 기준 = serialized bytes</h4>
 * <p>{@code redisSerializer.serialize(response).length} 기준 (ObjectMapper 직접 사용 금지)</p>
 *
 * @see <a href="https://github.com/issue/158">Issue #158: Expectation API 캐시 타겟 전환</a>
 */
@Slf4j
@Service
public class TotalExpectationCacheService {

    private static final String CACHE_NAME = "expectationResult";
    private static final int MAX_CACHE_BYTES = 5 * 1024; // 5KB
    private static final String KEY_VERSION = "v3";

    private final CacheManager l1CacheManager;
    private final CacheManager l2CacheManager;
    private final RedisSerializer<Object> redisSerializer;
    private final LogicExecutor executor;

    public TotalExpectationCacheService(
            @Qualifier("expectationL1CacheManager") CacheManager l1CacheManager,
            @Qualifier("expectationL2CacheManager") CacheManager l2CacheManager,
            @Qualifier("expectationCacheSerializer") RedisSerializer<Object> redisSerializer,
            LogicExecutor executor) {
        this.l1CacheManager = l1CacheManager;
        this.l2CacheManager = l2CacheManager;
        this.redisSerializer = redisSerializer;
        this.executor = executor;
    }

    /**
     * 캐시에서 유효한 결과 조회 (L1 → L2 순서)
     *
     * <p>L2 hit 시 L1 warm-up 수행</p>
     *
     * @param cacheKey 캐시 키
     * @return 캐시된 결과 (없으면 empty)
     */
    public Optional<TotalExpectationResponse> getValidCache(String cacheKey) {
        return executor.execute(() -> {
            // 1. L1 조회
            Cache l1 = l1CacheManager.getCache(CACHE_NAME);
            if (l1 != null) {
                TotalExpectationResponse l1Result = l1.get(cacheKey, TotalExpectationResponse.class);
                if (l1Result != null) {
                    log.info("[Cache] L1 HIT | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                            l1Result.getUserIgn(),
                            l1Result.getTotalCost(),
                            l1Result.getItems() != null ? l1Result.getItems().size() : 0);
                    return Optional.of(l1Result);
                }
            } else {
                log.warn("[Cache] L1 unavailable | cache={}", CACHE_NAME);
            }

            // 2. L2 조회
            Cache l2 = l2CacheManager.getCache(CACHE_NAME);
            if (l2 != null) {
                TotalExpectationResponse l2Result = l2.get(cacheKey, TotalExpectationResponse.class);
                if (l2Result != null) {
                    log.info("[Cache] L2 HIT | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                            l2Result.getUserIgn(),
                            l2Result.getTotalCost(),
                            l2Result.getItems() != null ? l2Result.getItems().size() : 0);
                    // L1 warm-up
                    if (l1 != null) {
                        l1.put(cacheKey, l2Result);
                        log.debug("[Cache] L1 warm-up completed");
                    }
                    return Optional.of(l2Result);
                }
            } else {
                log.warn("[Cache] L2 unavailable | cache={}", CACHE_NAME);
            }

            log.info("[Cache] MISS | dto=TotalExpectationResponse | maskedKey={}", maskKey(cacheKey));
            return Optional.empty();
        }, TaskContext.of("ExpectationCache", "GetValid", maskKey(cacheKey)));
    }

    /**
     * 캐시 저장 (P0-2 정책 적용)
     *
     * <h4>저장 순서</h4>
     * <ol>
     *   <li>L1 put (null-safe)</li>
     *   <li>Serialize + size guard</li>
     *   <li>5KB 초과 시 L2 저장 스킵</li>
     *   <li>L2 put (null-safe)</li>
     * </ol>
     *
     * @param cacheKey 캐시 키
     * @param response 저장할 응답
     */
    public void saveCache(String cacheKey, TotalExpectationResponse response) {
        executor.executeVoid(() -> {
            // 1) L1 put (null-safe)
            saveToL1(cacheKey, response);

            // 2) Serialize + size guard
            int size = serializeAndGetSize(cacheKey, response);
            if (size < 0) {
                return; // serialize 실패 시 L2 스킵
            }

            if (size > MAX_CACHE_BYTES) {
                log.info("[5KB Guard] L2 skip: {} bytes > {}", size, MAX_CACHE_BYTES);
                // TODO: metric - expectation.cache.payload.oversize.skip
                return;
            }

            // 3) L2 put
            saveToL2(cacheKey, response, size);
        }, TaskContext.of("ExpectationCache", "Save", maskKey(cacheKey)));
    }

    /**
     * L1 캐시에 저장 (평탄화)
     */
    private void saveToL1(String cacheKey, TotalExpectationResponse response) {
        Cache l1 = l1CacheManager.getCache(CACHE_NAME);
        if (l1 != null) {
            l1.put(cacheKey, response);
            log.info("[Cache] L1 SAVE | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={}",
                    response.getUserIgn(),
                    response.getTotalCost(),
                    response.getItems() != null ? response.getItems().size() : 0);
        } else {
            log.warn("[Cache] L1 unavailable | cache={}", CACHE_NAME);
        }
    }

    /**
     * Serialize 후 크기 반환 (P0-2: 실패 시 -1 반환, 예외 전파 없음)
     */
    private int serializeAndGetSize(String cacheKey, TotalExpectationResponse response) {
        return executor.executeOrCatch(
                () -> {
                    byte[] bytes = redisSerializer.serialize(response);
                    return (bytes != null) ? bytes.length : 0;
                },
                e -> {
                    // serialize 실패는 L2 스킵(정책) + 로그
                    log.warn("[Serialize Fail] err={}", e.toString());
                    log.debug("[Serialize Fail] maskedKey={}", maskKey(cacheKey));
                    // TODO: metric - expectation.cache.serialize.fail
                    return -1; // L2 스킵 시그널
                },
                TaskContext.of("ExpectationCache", "Serialize", maskKey(cacheKey))
        );
    }

    /**
     * L2 캐시에 저장 (P0-2: 실패해도 API 실패로 전파 금지)
     */
    private void saveToL2(String cacheKey, TotalExpectationResponse response, int size) {
        Cache l2 = l2CacheManager.getCache(CACHE_NAME);
        if (l2 == null) {
            log.warn("[Cache] L2 unavailable | cache={}", CACHE_NAME);
            return;
        }

        executor.executeOrCatch(
                () -> {
                    l2.put(cacheKey, response);
                    log.info("[Cache] L2 SAVE | dto=TotalExpectationResponse | userIgn={} | totalCost={} | items={} | size={}bytes",
                            response.getUserIgn(),
                            response.getTotalCost(),
                            response.getItems() != null ? response.getItems().size() : 0,
                            size);
                    return null;
                },
                e -> {
                    // L2 저장 실패해도 API 실패로 전파 금지 (P0-2)
                    log.warn("[Cache] L2 SAVE FAIL | dto=TotalExpectationResponse | userIgn={} | err={}",
                            response.getUserIgn(), e.toString());
                    return null;
                },
                TaskContext.of("ExpectationCache", "SaveL2", maskKey(cacheKey))
        );
    }

    /**
     * 캐시 키 생성
     *
     * <p>형식: expectation:v3:{ocid}:{fingerprint}:{tableVersionHash}:lv{logicVersion}</p>
     *
     * @param ocid 캐릭터 OCID
     * @param fingerprint equipment.updatedAt epoch second (null이면 "0")
     * @param tableVersionHash 테이블 버전 해시 (URL-safe)
     * @param logicVersion 계산 로직 버전
     * @return 캐시 키
     */
    public String buildCacheKey(String ocid, String fingerprint, String tableVersionHash, int logicVersion) {
        return String.format("expectation:%s:%s:%s:%s:lv%d",
                KEY_VERSION, ocid, fingerprint, tableVersionHash, logicVersion);
    }

    /**
     * 캐시 키 마스킹 (로그용)
     *
     * <p>P0-2 로그 정책: cacheKey 원문 로그 금지 → ocid 부분만 마스킹</p>
     *
     * @param key 원본 캐시 키
     * @return 마스킹된 키 (ocid 부분이 ***로 대체)
     */
    private String maskKey(String key) {
        if (key == null) return "null";
        // expectation:v3:ocid123:... → expectation:v3:oc***:...
        return key.replaceAll("(expectation:v\\d+:)[^:]+", "$1***");
    }
}
