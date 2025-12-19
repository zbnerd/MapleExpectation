package maple.expectation.service.v2.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.dto.CubeCalculationInput;
import maple.expectation.service.v2.CubeTrialsProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@Primary // 인터페이스 주입 시 실제 구현체 대신 프록시가 주입되도록 설정
public class CubeTrialsCachingProxy implements CubeTrialsProvider {

    private final CubeTrialsProvider target; // 실제 계산을 수행할 CubeServiceImpl
    private final Cache<String, Long> trialsCache;

    public CubeTrialsCachingProxy(CubeTrialsProvider cubeServiceImpl) {
        this.target = cubeServiceImpl;
        // Caffeine 캐시 설정
        this.trialsCache = Caffeine.newBuilder()
                .expireAfterAccess(30, TimeUnit.MINUTES) // 30분간 사용 안하면 제거
                .maximumSize(10_000) // 최대 1만 건 저장 (메모리 관리)
                .build();
    }

    @Override
    public Long calculateExpectedTrials(CubeCalculationInput input, CubeType type) {
        String cacheKey = generateCacheKey(input, type);

        // 캐시에 있으면 반환, 없으면 target(ServiceImpl) 호출하여 계산 후 캐시에 저장
        return trialsCache.get(cacheKey, key -> target.calculateExpectedTrials(input, type));
    }

    private String generateCacheKey(CubeCalculationInput input, CubeType type) {
        return String.format("%s_%d_%s_%s_%s", 
                type.name(), 
                input.getLevel(), 
                input.getPart(), 
                input.getGrade(), 
                input.getOptions());
    }
}