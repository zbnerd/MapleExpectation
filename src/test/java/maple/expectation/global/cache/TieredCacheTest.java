package maple.expectation.global.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * TieredCache 단위 테스트 (Issue #194)
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 * <p>Spring Context 없이 Mockito만으로 2층 캐시를 검증합니다.</p>
 *
 * <h4>테스트 범위</h4>
 * <ul>
 *   <li>L1 → L2 순차 조회 및 Backfill</li>
 *   <li>L2 → L1 순서 저장 (일관성)</li>
 *   <li>Single-flight 패턴 (분산 락)</li>
 *   <li>Graceful Degradation (L2 장애 시)</li>
 * </ul>
 */
@Tag("unit")
class TieredCacheTest {

    private static final String CACHE_NAME = "testCache";
    private static final Object KEY = "testKey";
    private static final String VALUE = "testValue";

    private Cache l1;
    private Cache l2;
    private LogicExecutor executor;
    private RedissonClient redissonClient;
    private MeterRegistry meterRegistry;
    private TieredCache tieredCache;

    @BeforeEach
    void setUp() {
        l1 = mock(Cache.class);
        l2 = mock(Cache.class);
        executor = createMockLogicExecutor();
        redissonClient = mock(RedissonClient.class);
        meterRegistry = new SimpleMeterRegistry();

        given(l2.getName()).willReturn(CACHE_NAME);

        tieredCache = new TieredCache(l1, l2, executor, redissonClient, meterRegistry, "test-instance", event -> { });
    }

    @Nested
    @DisplayName("기본 메서드")
    class BasicMethodsTest {

        @Test
        @DisplayName("getName은 L2 캐시명 반환")
        void shouldReturnL2CacheName() {
            assertThat(tieredCache.getName()).isEqualTo(CACHE_NAME);
        }

        @Test
        @DisplayName("getNativeCache는 L2 네이티브 캐시 반환")
        void shouldReturnL2NativeCache() {
            Object nativeCache = new Object();
            given(l2.getNativeCache()).willReturn(nativeCache);

            assertThat(tieredCache.getNativeCache()).isSameAs(nativeCache);
        }
    }

    @Nested
    @DisplayName("캐시 조회 get(Object)")
    class GetTest {

        @Test
        @DisplayName("L1 히트 시 L1에서 반환")
        void shouldReturnFromL1WhenHit() {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(wrapper);

            // when
            Cache.ValueWrapper result = tieredCache.get(KEY);

            // then
            assertThat(result.get()).isEqualTo(VALUE);
            verify(l2, never()).get(KEY);
        }

        @Test
        @DisplayName("L1 미스, L2 히트 시 L1 Backfill")
        void shouldBackfillL1WhenL2Hit() {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(wrapper);

            // when
            Cache.ValueWrapper result = tieredCache.get(KEY);

            // then
            assertThat(result.get()).isEqualTo(VALUE);
            verify(l1).put(KEY, VALUE);
        }

        @Test
        @DisplayName("L1, L2 모두 미스 시 null 반환")
        void shouldReturnNullWhenBothMiss() {
            // given
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(null);

            // when
            Cache.ValueWrapper result = tieredCache.get(KEY);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("캐시 저장 put")
    class PutTest {

        @Test
        @DisplayName("L2 성공 시 L1도 저장")
        void shouldPutBothLayersWhenL2Success() {
            // when
            tieredCache.put(KEY, VALUE);

            // then
            verify(l2).put(KEY, VALUE);
            verify(l1).put(KEY, VALUE);
        }

        @Test
        @DisplayName("L2 실패 시 L1 저장 스킵 (일관성)")
        void shouldSkipL1WhenL2Fails() {
            // given - L2 저장 실패 시뮬레이션
            executor = createFailingL2Executor();
            tieredCache = new TieredCache(l1, l2, executor, redissonClient, meterRegistry, "test-instance", event -> { });

            // when
            tieredCache.put(KEY, VALUE);

            // then
            verify(l1, never()).put(KEY, VALUE);
        }
    }

    @Nested
    @DisplayName("캐시 삭제 evict/clear")
    class EvictClearTest {

        @Test
        @DisplayName("evict 시 양쪽 레이어 모두 삭제")
        void shouldEvictBothLayers() {
            // when
            tieredCache.evict(KEY);

            // then
            verify(l1).evict(KEY);
            verify(l2).evict(KEY);
        }

        @Test
        @DisplayName("clear 시 양쪽 레이어 모두 클리어")
        void shouldClearBothLayers() {
            // when
            tieredCache.clear();

            // then
            verify(l1).clear();
            verify(l2).clear();
        }
    }

    @Nested
    @DisplayName("타입 캐스팅 get(Object, Class)")
    class TypedGetTest {

        @Test
        @DisplayName("타입 캐스팅 성공")
        void shouldCastToType() {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(wrapper);

            // when
            String result = tieredCache.get(KEY, String.class);

            // then
            assertThat(result).isEqualTo(VALUE);
        }

        @Test
        @DisplayName("캐시 미스 시 null 반환")
        void shouldReturnNullOnMiss() {
            // given
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(null);

            // when
            String result = tieredCache.get(KEY, String.class);

            // then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("ValueLoader get(Object, Callable)")
    class ValueLoaderTest {

        @Test
        @DisplayName("L1 히트 시 valueLoader 호출 안함")
        void shouldNotCallLoaderWhenL1Hit() throws Exception {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(wrapper);
            Callable<String> loader = mock(Callable.class);

            // when
            String result = tieredCache.get(KEY, loader);

            // then
            assertThat(result).isEqualTo(VALUE);
            verify(loader, never()).call();
        }

        @Test
        @DisplayName("L2 히트 시 valueLoader 호출 안함, L1 Backfill")
        void shouldNotCallLoaderWhenL2Hit() throws Exception {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(wrapper);
            Callable<String> loader = mock(Callable.class);

            // when
            String result = tieredCache.get(KEY, loader);

            // then
            assertThat(result).isEqualTo(VALUE);
            verify(loader, never()).call();
            verify(l1).put(KEY, VALUE);
        }

        @Test
        @DisplayName("캐시 미스 시 valueLoader 호출 및 캐시 저장")
        void shouldCallLoaderOnCacheMiss() throws Exception {
            // given
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(null);

            RLock lock = createMockLock(true);
            given(redissonClient.getLock(anyString())).willReturn(lock);

            Callable<String> loader = () -> "loadedValue";

            // when
            String result = tieredCache.get(KEY, loader);

            // then
            assertThat(result).isEqualTo("loadedValue");
            verify(l2).put(KEY, "loadedValue");
            verify(l1).put(KEY, "loadedValue");
        }

        @Test
        @DisplayName("락 획득 실패 시 Fallback으로 직접 실행")
        void shouldFallbackWhenLockFails() throws Exception {
            // given
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(null);

            RLock lock = createMockLock(false);
            given(redissonClient.getLock(anyString())).willReturn(lock);

            Callable<String> loader = () -> "fallbackValue";

            // when
            String result = tieredCache.get(KEY, loader);

            // then
            assertThat(result).isEqualTo("fallbackValue");
            // 락 실패 메트릭 확인
            Counter lockFailure = meterRegistry.find("cache.lock.failure").counter();
            assertThat(lockFailure).isNotNull();
        }

        @Test
        @DisplayName("Double-check: 락 획득 후 L2에서 발견 시 valueLoader 호출 안함")
        void shouldSkipLoaderIfFoundAfterLock() throws Exception {
            // given - 첫 조회 시 미스, 락 후 double-check에서 발견
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY))
                    .willReturn(null)  // 첫 조회
                    .willReturn(() -> "doubleCheckValue");  // double-check

            RLock lock = createMockLock(true);
            given(redissonClient.getLock(anyString())).willReturn(lock);

            Callable<String> loader = mock(Callable.class);

            // when
            String result = tieredCache.get(KEY, loader);

            // then
            assertThat(result).isEqualTo("doubleCheckValue");
            verify(loader, never()).call();
        }
    }

    @Nested
    @DisplayName("메트릭")
    class MetricsTest {

        @Test
        @DisplayName("L1 히트 시 메트릭 기록")
        void shouldRecordL1HitMetric() {
            // given
            Cache.ValueWrapper wrapper = () -> VALUE;
            given(l1.get(KEY)).willReturn(wrapper);

            // when
            tieredCache.get(KEY);

            // then
            Counter l1HitCounter = meterRegistry.find("cache.hit")
                    .tag("layer", "L1")
                    .counter();
            assertThat(l1HitCounter).isNotNull();
            assertThat(l1HitCounter.count()).isGreaterThan(0);
        }

        @Test
        @DisplayName("L2 히트 시 메트릭 기록")
        void shouldRecordL2HitMetric() {
            // given
            given(l1.get(KEY)).willReturn(null);
            given(l2.get(KEY)).willReturn(() -> VALUE);

            // when
            tieredCache.get(KEY);

            // then
            Counter l2HitCounter = meterRegistry.find("cache.hit")
                    .tag("layer", "L2")
                    .counter();
            assertThat(l2HitCounter).isNotNull();
            assertThat(l2HitCounter.count()).isGreaterThan(0);
        }
    }

    // ==================== Helper Methods ====================

    @SuppressWarnings("unchecked")
    private LogicExecutor createMockLogicExecutor() {
        LogicExecutor mockExecutor = mock(LogicExecutor.class);

        // execute: 실제 작업 실행
        given(mockExecutor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    return task.get();
                });

        // executeVoid: 실제 작업 실행
        doAnswer(invocation -> {
            ThrowingRunnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(mockExecutor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        // executeOrDefault: 실제 작업 실행, 예외 시 기본값
        given(mockExecutor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    Object defaultValue = invocation.getArgument(1);
                    try {
                        return task.get();
                    } catch (Throwable e) {
                        return defaultValue;
                    }
                });

        // executeWithTranslation: 예외 번역
        given(mockExecutor.executeWithTranslation(any(ThrowingSupplier.class), any(ExceptionTranslator.class), any(TaskContext.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    return task.get();
                });

        // executeWithFinally: try-finally 패턴
        given(mockExecutor.executeWithFinally(any(ThrowingSupplier.class), any(Runnable.class), any(TaskContext.class)))
                .willAnswer(invocation -> {
                    ThrowingSupplier<?> task = invocation.getArgument(0);
                    Runnable finalizer = invocation.getArgument(1);
                    try {
                        return task.get();
                    } finally {
                        finalizer.run();
                    }
                });

        return mockExecutor;
    }

    @SuppressWarnings("unchecked")
    private LogicExecutor createFailingL2Executor() {
        LogicExecutor mockExecutor = mock(LogicExecutor.class);

        // executeOrDefault: L2 관련 작업은 기본값 반환 (실패 시뮬레이션)
        given(mockExecutor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
                .willAnswer(invocation -> invocation.getArgument(1)); // 항상 기본값 반환

        // executeVoid는 정상 동작
        doAnswer(invocation -> {
            ThrowingRunnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(mockExecutor).executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

        return mockExecutor;
    }

    private RLock createMockLock(boolean acquireSuccess) throws InterruptedException {
        RLock lock = mock(RLock.class);
        given(lock.tryLock(anyLong(), any(TimeUnit.class))).willReturn(acquireSuccess);
        given(lock.isHeldByCurrentThread()).willReturn(acquireSuccess);
        return lock;
    }
}
