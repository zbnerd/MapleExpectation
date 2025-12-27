package maple.expectation.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();

        // 1. 장비 정보 캐시 (이슈 #11: 15분, DB 동기화 최적화)
        CaffeineCache equipmentCache = buildCaffeineCache("equipment", 15, 5000);

        // 2. 계산 결과 캐시 (이슈 #12: 20분, 연산 비용 절감)
        CaffeineCache calculationCache = buildCaffeineCache("cubeTrials", 20, 10000);

        // 3. OCID 캐시 (자주 안 변하므로 더 길게 가져가도 됨)
        CaffeineCache ocidCache = buildCaffeineCache("ocidCache", 60, 10000);

        cacheManager.setCaches(Arrays.asList(equipmentCache, calculationCache, ocidCache));
        return cacheManager;
    }

    private CaffeineCache buildCaffeineCache(String name, int durationMinute, int maximumSize) {
        return new CaffeineCache(name,
                Caffeine.newBuilder()
                        .expireAfterWrite(durationMinute, TimeUnit.MINUTES)
                        .maximumSize(maximumSize)
                        .build());
    }
}