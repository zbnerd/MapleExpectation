package maple.expectation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import maple.expectation.global.cache.RestrictedCacheManager;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.global.executor.LogicExecutor;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * TieredCacheManager 생성 및 의존성 주입
     *
     * <h4>Issue #148: 분산 락 및 메트릭 지원</h4>
     * <ul>
     *   <li>RedissonClient: 분산 락 기반 Single-flight 패턴</li>
     *   <li>MeterRegistry: 캐시 히트/미스 메트릭 수집</li>
     * </ul>
     *
     * @Primary 기존 @Cacheable 인프라 영향 최소화
     */
    @Bean
    @Primary
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            LogicExecutor executor,
            RedissonClient redissonClient,  // Issue #148: 분산 락용
            MeterRegistry meterRegistry) {  // Issue #148: 메트릭 수집용

        return new TieredCacheManager(
                createL1Manager(),
                createL2Manager(connectionFactory),
                executor,
                redissonClient,
                meterRegistry
        );
    }

    /**
     * 🧊 L1 (Caffeine): 로컬 메모리 - Near Cache 전략
     */
    private CacheManager createL1Manager() {
        CaffeineCacheManager l1Manager = new CaffeineCacheManager();

        l1Manager.registerCustomCache("equipment",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());

        l1Manager.registerCustomCache("cubeTrials",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());

        l1Manager.registerCustomCache("ocidCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());

        // #240 V4: GZIP 압축 전체 응답 캐시
        l1Manager.registerCustomCache("expectationV4",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .recordStats()
                        .build());

        return l1Manager;
    }

    /**
     * 🚩 L2 (Redis): 분산 저장소 - 중앙 캐시 전략
     *
     * <h4>Issue #240: cubeTrials 캐시 ClassCastException 수정</h4>
     * <ul>
     *   <li>GenericJackson2JsonRedisSerializer는 Double 타입 보존 실패</li>
     *   <li>JdkSerializationRedisSerializer 사용으로 타입 안전성 확보</li>
     * </ul>
     */
    private CacheManager createL2Manager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        // [Issue #240] cubeTrials 전용 설정: JdkSerializer로 Double 타입 보존
        // GenericJackson2JsonRedisSerializer는 primitive wrapper(Double)를 String으로 역직렬화하는 버그 존재
        RedisCacheConfiguration cubeTrialsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(20))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.java()));

        // #240 V4: GZIP 압축 byte[] 전용 설정 (JdkSerializer로 바이트 배열 보존)
        RedisCacheConfiguration expectationV4Config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.java()));

        Map<String, RedisCacheConfiguration> configurations = new HashMap<>();

        // [이슈 #11] DB(15분)보다 짧게 -> 10분
        configurations.put("equipment", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // [이슈 #12, #240] cubeTrials: JdkSerializer 사용 (Double 타입 보존)
        configurations.put("cubeTrials", cubeTrialsConfig);

        // OCID: 충분히 길게 -> 60분
        configurations.put("ocidCache", defaultConfig.entryTtl(Duration.ofMinutes(60)));

        // #240 V4: GZIP 압축 전체 응답 캐시 (byte[] 타입 보존)
        configurations.put("expectationV4", expectationV4Config);

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configurations)
                .build();
    }

    // ==================== Issue #158: Expectation 전용 캐시 인프라 ====================

    /**
     * Expectation 전용 Typed Serializer (M2 표준 - Spring Data Redis 3.x)
     *
     * <h4>설계 의도</h4>
     * <ul>
     *   <li>@class 메타데이터 제거 → 5KB 압박 완화</li>
     *   <li>타입 복원 100% 보장 (LinkedHashMap 복원 리스크 제거)</li>
     *   <li>Spring Data Redis 3.x: ObjectMapper 생성자 직접 전달 (setObjectMapper deprecated 대응)</li>
     * </ul>
     */
    @Bean
    @Qualifier("expectationCacheSerializer")
    public RedisSerializer<Object> expectationCacheSerializer(ObjectMapper objectMapper) {
        // Spring Data Redis 3.x: new Jackson2JsonRedisSerializer(ObjectMapper, Class)
        Jackson2JsonRedisSerializer<TotalExpectationResponse> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, TotalExpectationResponse.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        RedisSerializer<Object> casted = (RedisSerializer) serializer;
        return casted;
    }

    /**
     * Expectation 전용 L1 CacheManager (Caffeine)
     *
     * <p>Blocker C 해결: Expectation 경로에서 equipment L1-only가 실제로 동작하도록 equipment 캐시도 등록</p>
     * <p>EquipmentService.resolveEquipmentData()가 getValidCacheL1Only()/saveCacheL1Only() 사용</p>
     */
    @Bean(name = "expectationL1CacheManager")
    public CacheManager expectationL1CacheManager() {
        CaffeineCacheManager l1Manager = new CaffeineCacheManager();

        // Expectation 결과 캐시
        l1Manager.registerCustomCache("expectationResult",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .recordStats()
                        .build());

        // Expectation 경로 equipment L1-only 캐시 (L2 우회용)
        l1Manager.registerCustomCache("equipment",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .recordStats()
                        .build());

        return l1Manager;
    }

    /**
     * Expectation 전용 L2 CacheManager (Redis + RestrictedCacheManager)
     * - P0-7/B3: equipment 구조적 봉쇄
     * - expectationResult만 허용
     */
    @Bean(name = "expectationL2CacheManager")
    public CacheManager expectationL2CacheManager(
            RedisConnectionFactory connectionFactory,
            @Qualifier("expectationCacheSerializer") RedisSerializer<Object> serializer) {

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        // RestrictedCacheManager가 기본 방어이므로 disableCreateOnMissingCache()는 제거 (버전 호환성)
        RedisCacheManager delegate = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .initialCacheNames(Set.of("expectationResult"))
                .build();

        // 항상 RestrictedCacheManager로 래핑 (버전 무관하게 구조적 봉쇄)
        return new RestrictedCacheManager(delegate, Set.of("expectationResult"));
    }
}