package maple.expectation.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching // 💡 핵심: 스프링의 AOP 기반 캐싱 기능을 활성화합니다!
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("cubeTrials", "ocidCache");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES) // 기존 프록시 설정 유지
                .maximumSize(10_000));
        return cacheManager;
    }
}