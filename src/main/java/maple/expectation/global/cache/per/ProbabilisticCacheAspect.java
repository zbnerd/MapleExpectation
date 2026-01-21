package maple.expectation.global.cache.per;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * PER (Probabilistic Early Recomputation) AOP Aspect (#219)
 *
 * <h3>Cache Stampede 방지</h3>
 * <p>X-Fetch 알고리즘을 사용하여 Lock 없이 확률적 백그라운드 갱신을 수행.</p>
 *
 * <h4>처리 흐름</h4>
 * <ol>
 *   <li>Cache Miss → 동기 실행 후 캐시 저장</li>
 *   <li>Cache Hit + PER 당첨 → 비동기 갱신 + Stale 데이터 반환</li>
 *   <li>Cache Hit + PER 미당첨 → 캐시 데이터 반환</li>
 * </ol>
 *
 * <h4>Non-Blocking 보장</h4>
 * <ul>
 *   <li>캐시 Hit 시 항상 즉시 반환 (Stale 허용)</li>
 *   <li>백그라운드 갱신은 전용 Thread Pool에서 Fire & Forget</li>
 *   <li>갱신 실패해도 기존 데이터 유지</li>
 * </ul>
 *
 * @see ProbabilisticCache
 * @see CachedWrapper
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ProbabilisticCacheAspect {

    private final RedissonClient redissonClient;
    private final Executor perCacheExecutor;
    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramDiscoverer = new DefaultParameterNameDiscoverer();

    // P2-GREEN-01: JavaType 캐싱으로 성능 최적화
    private final ConcurrentHashMap<Method, JavaType> wrapperTypeCache = new ConcurrentHashMap<>();

    @Around("@annotation(probabilisticCache)")
    public Object handleCache(ProceedingJoinPoint joinPoint, ProbabilisticCache probabilisticCache) throws Throwable {
        String cacheKey = generateKey(joinPoint, probabilisticCache);
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 1. 캐시 조회
        RBucket<String> bucket = redissonClient.getBucket(cacheKey);
        String cachedJson = bucket.get();

        // 2. Cache Miss → 동기 실행
        if (cachedJson == null) {
            log.debug("🔴 [PER] Cache Miss: {}", cacheKey);
            return recomputeAndCache(joinPoint, cacheKey, probabilisticCache);
        }

        // 3. PR #238 Fix: JavaType을 사용한 역직렬화 (제네릭 타입 보존)
        CachedWrapper<Object> cached = deserializeWrapperSafely(cachedJson, cacheKey, method);
        if (cached == null) {
            log.warn("⚠️ [PER] 역직렬화 실패, 재계산: {}", cacheKey);
            return recomputeAndCache(joinPoint, cacheKey, probabilisticCache);
        }

        // 4. Cache Hit → PER 알고리즘 체크
        if (cached.shouldRefresh(probabilisticCache.beta())) {
            log.info("🎲 [PER] 조기 갱신 당첨! 백그라운드 갱신 시작 (Key: {}, TTL 남음: {}ms)",
                    cacheKey, cached.remainingTtl());

            // 비동기 갱신 (Fire & Forget) - LogicExecutor 패턴 적용
            perCacheExecutor.execute(
                    () -> refreshInBackground(joinPoint, cacheKey, probabilisticCache)
            );
        }

        // 5. Stale 데이터 즉시 반환 (Non-Blocking)
        log.debug("🟢 [PER] Cache Hit: {} (stale: {})", cacheKey, cached.isExpired());
        return cached.getValue();
    }

    /**
     * 원본 메서드 실행 후 캐시 저장
     */
    private Object recomputeAndCache(
            ProceedingJoinPoint joinPoint,
            String cacheKey,
            ProbabilisticCache annotation
    ) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long delta = System.currentTimeMillis() - start;

        // CachedWrapper 생성 (값 + delta + expiry)
        CachedWrapper<Object> wrapper = CachedWrapper.of(result, delta, annotation.ttlSeconds());

        // Redis 저장 (TTL 포함) - LogicExecutor 패턴
        RBucket<String> bucket = redissonClient.getBucket(cacheKey);
        String json = serializeWrapperSafely(wrapper, cacheKey);
        if (json != null) {
            bucket.set(json, Duration.ofSeconds(annotation.ttlSeconds()));
            log.debug("💾 [PER] 캐시 저장: key={}, delta={}ms, ttl={}s", cacheKey, delta, annotation.ttlSeconds());
        }

        return result;
    }

    /**
     * 백그라운드 갱신 (LogicExecutor 패턴)
     *
     * <p>비동기 작업에서 발생하는 예외를 LogicExecutor로 처리하여
     * CLAUDE.md Section 12 (Zero try-catch) 위반 방지</p>
     */
    private void refreshInBackground(
            ProceedingJoinPoint joinPoint,
            String cacheKey,
            ProbabilisticCache annotation
    ) {
        TaskContext context = TaskContext.of("PER", "AsyncRefresh", cacheKey);

        executor.executeVoid(
                () -> {
                    recomputeAndCache(joinPoint, cacheKey, annotation);
                    log.debug("✅ [PER] 백그라운드 갱신 완료: {}", cacheKey);
                },
                context
        );
    }

    /**
     * CachedWrapper → JSON 직렬화 (LogicExecutor 패턴)
     *
     * @param wrapper 캐시 래퍼
     * @param cacheKey 캐시 키 (로깅용)
     * @return JSON 문자열, 실패 시 null
     */
    private String serializeWrapperSafely(CachedWrapper<Object> wrapper, String cacheKey) {
        TaskContext context = TaskContext.of("PER", "Serialize", cacheKey);

        return executor.executeOrDefault(
                () -> objectMapper.writeValueAsString(wrapper),
                null,
                context
        );
    }

    /**
     * PR #238 Fix: JavaType을 사용한 JSON → CachedWrapper 역직렬화
     *
     * <h4>변경 전 (버그)</h4>
     * <p>{@code CachedWrapper.class}로 역직렬화 시 제네릭 타입 정보 손실 → ClassCastException</p>
     *
     * <h4>변경 후</h4>
     * <p>메서드 반환 타입에서 JavaType을 추출하여 정확한 타입으로 역직렬화</p>
     *
     * @param json JSON 문자열
     * @param cacheKey 캐시 키 (로깅용)
     * @param method 원본 메서드 (반환 타입 추출용)
     * @return CachedWrapper, 실패 시 null
     */
    @SuppressWarnings("unchecked")
    private CachedWrapper<Object> deserializeWrapperSafely(String json, String cacheKey, Method method) {
        TaskContext context = TaskContext.of("PER", "Deserialize", cacheKey);

        return executor.executeOrDefault(
                () -> {
                    // P2-GREEN-01: JavaType 캐싱 적용
                    JavaType wrapperType = wrapperTypeCache.computeIfAbsent(method, this::buildWrapperType);
                    return objectMapper.readValue(json, wrapperType);
                },
                null,
                context
        );
    }

    /**
     * P2-GREEN-01: 메서드별 CachedWrapper JavaType 생성 (캐싱용)
     */
    private JavaType buildWrapperType(Method method) {
        java.lang.reflect.Type returnType = method.getGenericReturnType();
        JavaType valueType = objectMapper.getTypeFactory().constructType(returnType);
        return objectMapper.getTypeFactory()
                .constructParametricType(CachedWrapper.class, valueType);
    }

    /**
     * SpEL 기반 캐시 키 생성
     */
    private String generateKey(ProceedingJoinPoint joinPoint, ProbabilisticCache annotation) {
        String keyExpression = annotation.key();
        String cacheName = annotation.cacheName();

        // key가 비어있으면 메서드 시그니처 사용
        if (keyExpression.isEmpty()) {
            return cacheName + ":" + joinPoint.getSignature().toShortString();
        }

        // SpEL 파싱
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        EvaluationContext evalContext = new StandardEvaluationContext();
        String[] paramNames = paramDiscoverer.getParameterNames(method);

        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                evalContext.setVariable(paramNames[i], args[i]);
            }
        }

        Object keyValue = parser.parseExpression(keyExpression).getValue(evalContext);
        return cacheName + ":" + keyValue;
    }
}
