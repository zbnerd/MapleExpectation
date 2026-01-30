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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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

/**
 * 캐시 설정 (P1-2: 외부화, P1-9: 중복 제거)
 *
 * <h4>P1-2: TTL/Size 하드코딩 → CacheProperties 외부화</h4>
 * <p>specs.forEach()로 동적 등록하여 신규 캐시 추가 시 YAML만 변경</p>
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    /**
     * TieredCacheManager 생성 및 의존성 주입
     *
     * <h4>Issue #148: 분산 락 및 메트릭 지원</h4>
     * <h4>P0-4: lockWaitSeconds 외부 설정 (CacheProperties)</h4>
     *
     * @Primary 기존 @Cacheable 인프라 영향 최소화
     */
    @Bean
    @Primary
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            LogicExecutor executor,
            RedissonClient redissonClient,
            MeterRegistry meterRegistry,
            CacheProperties cacheProperties) {

        return new TieredCacheManager(
                createL1Manager(cacheProperties),
                createL2Manager(connectionFactory, cacheProperties),
                executor,
                redissonClient,
                meterRegistry,
                cacheProperties.getSingleflight().getLockWaitSeconds()
        );
    }

    /**
     * L1 (Caffeine): 로컬 메모리 - Near Cache 전략
     *
     * <h4>P1-2: CacheProperties에서 동적 등록</h4>
     */
    private CacheManager createL1Manager(CacheProperties cacheProperties) {
        CaffeineCacheManager l1Manager = new CaffeineCacheManager();

        cacheProperties.getSpecs().forEach((name, spec) ->
                l1Manager.registerCustomCache(name,
                        Caffeine.newBuilder()
                                .expireAfterWrite(spec.getL1TtlMinutes(), TimeUnit.MINUTES)
                                .maximumSize(spec.getL1MaxSize())
                                .recordStats()
                                .build())
        );

        return l1Manager;
    }

    /**
     * L2 (Redis): 분산 저장소 - 중앙 캐시 전략
     *
     * <h4>P1-2: CacheProperties에서 동적 등록</h4>
     * <h4>Issue #240: cubeTrials 캐시 ClassCastException 수정</h4>
     * <ul>
     *   <li>GenericJackson2JsonRedisSerializer는 Double 타입 보존 실패</li>
     *   <li>JdkSerializationRedisSerializer 사용으로 타입 안전성 확보</li>
     * </ul>
     */
    private CacheManager createL2Manager(RedisConnectionFactory factory, CacheProperties cacheProperties) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> configurations = new HashMap<>();

        cacheProperties.getSpecs().forEach((name, spec) -> {
            RedisSerializer<?> serializer = resolveSerializer(spec.getL2Serializer());
            RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(spec.getL2TtlMinutes()))
                    .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                    .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
            configurations.put(name, config);
        });

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configurations)
                .build();
    }

    /**
     * L2 직렬화 방식 결정 (P1-2)
     *
     * <ul>
     *   <li>json: GenericJackson2JsonRedisSerializer (기본)</li>
     *   <li>jdk: JdkSerializationRedisSerializer (Double 타입 보존 등)</li>
     * </ul>
     */
    private RedisSerializer<?> resolveSerializer(String type) {
        return "jdk".equalsIgnoreCase(type)
                ? RedisSerializer.java()
                : new GenericJackson2JsonRedisSerializer();
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
     * <p>P1-9: equipment L1은 cacheManager의 L1에서 동일 TTL/MaxSize 사용</p>
     * <p>Blocker C 해결: Expectation 경로에서 equipment L1-only가 실제로 동작하도록 equipment 캐시도 등록</p>
     */
    @Bean(name = "expectationL1CacheManager")
    public CacheManager expectationL1CacheManager(CacheProperties cacheProperties) {
        CaffeineCacheManager l1Manager = new CaffeineCacheManager();

        // Expectation 결과 캐시
        l1Manager.registerCustomCache("expectationResult",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .recordStats()
                        .build());

        // P1-9: equipment L1-only 캐시 (CacheProperties에서 TTL/Size 참조)
        CacheProperties.CacheSpec equipmentSpec = cacheProperties.getSpecs().get("equipment");
        if (equipmentSpec != null) {
            l1Manager.registerCustomCache("equipment",
                    Caffeine.newBuilder()
                            .expireAfterWrite(equipmentSpec.getL1TtlMinutes(), TimeUnit.MINUTES)
                            .maximumSize(equipmentSpec.getL1MaxSize())
                            .recordStats()
                            .build());
        }

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
