package maple.expectation.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.global.cache.TieredCacheManager;
import maple.expectation.repository.v2.CharacterEquipmentRepository;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory) {

        return new TieredCacheManager(
                createL1Manager(),
                createL2Manager(connectionFactory)
        );
    }

    /**
     * 🧊 L1 (Caffeine): 로컬 메모리 - 가장 짧은 수명 (Near Cache)
     */
    private CacheManager createL1Manager() {
        CaffeineCacheManager l1Manager = new CaffeineCacheManager();

        l1Manager.registerCustomCache("equipment",
                Caffeine.newBuilder()
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .maximumSize(1000)
                        .build());

        l1Manager.registerCustomCache("cubeTrials",
                Caffeine.newBuilder()
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .build());

        l1Manager.registerCustomCache("ocidCache",
                Caffeine.newBuilder()
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .maximumSize(5000)
                        .build());

        return l1Manager;
    }

    /**
     * 🚩 L2 (Redis): 분산 저장소 - 중간 수명
     */
    private CacheManager createL2Manager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        Map<String, RedisCacheConfiguration> configurations = new HashMap<>();

        // [이슈 #11] DB(15분)보다 짧게 -> 10분 🚀
        configurations.put("equipment", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // [이슈 #12] 원본(이슈 기준)보다 짧게 혹은 맞춰서 -> 20분
        configurations.put("cubeTrials", defaultConfig.entryTtl(Duration.ofMinutes(20)));

        // OCID: 충분히 길게 -> 60분
        configurations.put("ocidCache", defaultConfig.entryTtl(Duration.ofMinutes(60)));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configurations)
                .build();
    }
}