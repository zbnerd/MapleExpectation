package maple.expectation.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        // 💡 "equipment" 영역 추가
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("cubeTrials", "ocidCache", "equipment");

        cacheManager.setCaffeine(Caffeine.newBuilder()
                // 💡 이슈 #11 정책: 15분 후 만료 (Write 기준)
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .maximumSize(10_000));
        return cacheManager;
    }
}