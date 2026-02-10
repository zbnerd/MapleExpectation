package maple.expectation.service.v2.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.function.Function;
import maple.expectation.external.dto.v2.TotalExpectationResponse;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * TotalExpectationCacheService 단위 테스트
 *
 * <h4>P0-2: saveCache() L2→L1 순서 검증</h4>
 *
 * <p>TieredCache 핵심 불변식 "L2 저장 → L1 저장" 순서 준수 확인
 *
 * <h4>경량 테스트 (CLAUDE.md Section 25)</h4>
 *
 * <p>Spring Context 없이 Mockito만으로 검증
 */
@Tag("unit")
class TotalExpectationCacheServiceTest {

  private static final String CACHE_NAME = "expectationResult";

  private CacheManager l1CacheManager;
  private CacheManager l2CacheManager;
  private RedisSerializer<Object> redisSerializer;
  private LogicExecutor executor;
  private MeterRegistry meterRegistry;

  private Cache l1Cache;
  private Cache l2Cache;

  private TotalExpectationCacheService service;

  @BeforeEach
  void setUp() {
    l1CacheManager = mock(CacheManager.class);
    l2CacheManager = mock(CacheManager.class);
    redisSerializer = mock(RedisSerializer.class);
    executor = createPassThroughExecutor();
    meterRegistry = new SimpleMeterRegistry();

    l1Cache = mock(Cache.class);
    l2Cache = mock(Cache.class);

    given(l1CacheManager.getCache(CACHE_NAME)).willReturn(l1Cache);
    given(l2CacheManager.getCache(CACHE_NAME)).willReturn(l2Cache);

    service =
        new TotalExpectationCacheService(
            l1CacheManager, l2CacheManager, redisSerializer, executor, meterRegistry);
  }

  @Nested
  @DisplayName("P0-2: saveCache() L2→L1 순서 검증")
  class SaveCacheOrderTest {

    @Test
    @DisplayName("saveCache() 시 L2 저장이 L1 저장보다 먼저 실행되어야 한다")
    void shouldSaveToL2BeforeL1() {
      // given
      TotalExpectationResponse response = createTestResponse();
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      byte[] serialized = new byte[100]; // 5KB 이내
      given(redisSerializer.serialize(response)).willReturn(serialized);

      // when
      service.saveCache(cacheKey, response);

      // then: L2 → L1 순서 보장 (InOrder)
      InOrder inOrder = inOrder(l2Cache, l1Cache);
      inOrder.verify(l2Cache).put(eq(cacheKey), eq(response));
      inOrder.verify(l1Cache).put(eq(cacheKey), eq(response));
    }

    @Test
    @DisplayName("5KB 초과 시 L2 저장 스킵, L1만 저장")
    void shouldSkipL2WhenOversized() {
      // given
      TotalExpectationResponse response = createTestResponse();
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      byte[] oversized = new byte[6 * 1024]; // 6KB > 5KB
      given(redisSerializer.serialize(response)).willReturn(oversized);

      // when
      service.saveCache(cacheKey, response);

      // then: L2 저장 안 함, L1만 저장
      verify(l2Cache, never()).put(any(), any());
      verify(l1Cache).put(eq(cacheKey), eq(response));
    }

    @Test
    @DisplayName("직렬화 실패 시 L2 스킵, L1만 저장")
    void shouldSaveOnlyToL1WhenSerializationFails() {
      // given
      TotalExpectationResponse response = createTestResponse();
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      given(redisSerializer.serialize(response)).willThrow(new RuntimeException("Serialize error"));

      // when
      service.saveCache(cacheKey, response);

      // then: L2 저장 안 함, L1만 저장 (로컬 성능 보장)
      verify(l2Cache, never()).put(any(), any());
      verify(l1Cache).put(eq(cacheKey), eq(response));
    }
  }

  @Nested
  @DisplayName("getValidCache() 조회 검증")
  class GetValidCacheTest {

    @Test
    @DisplayName("L1 히트 시 L2 조회 안 함")
    void shouldReturnFromL1WithoutL2() {
      // given
      TotalExpectationResponse expected = createTestResponse();
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      given(l1Cache.get(cacheKey, TotalExpectationResponse.class)).willReturn(expected);

      // when
      var result = service.getValidCache(cacheKey);

      // then
      assertThat(result).isPresent();
      assertThat(result.get().getUserIgn()).isEqualTo("TestUser123");
      verify(l2Cache, never()).get(any(), any(Class.class));
    }

    @Test
    @DisplayName("L1 미스, L2 히트 시 L1 warm-up")
    void shouldWarmUpL1WhenL2Hit() {
      // given
      TotalExpectationResponse expected = createTestResponse();
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      given(l1Cache.get(cacheKey, TotalExpectationResponse.class)).willReturn(null);
      given(l2Cache.get(cacheKey, TotalExpectationResponse.class)).willReturn(expected);

      // when
      var result = service.getValidCache(cacheKey);

      // then
      assertThat(result).isPresent();
      verify(l1Cache).put(cacheKey, expected); // L1 warm-up
    }

    @Test
    @DisplayName("L1, L2 모두 미스 시 empty 반환")
    void shouldReturnEmptyWhenBothMiss() {
      // given
      String cacheKey = "expectation:v3:test-ocid:123:abc:lv1";
      given(l1Cache.get(cacheKey, TotalExpectationResponse.class)).willReturn(null);
      given(l2Cache.get(cacheKey, TotalExpectationResponse.class)).willReturn(null);

      // when
      var result = service.getValidCache(cacheKey);

      // then
      assertThat(result).isEmpty();
    }
  }

  // ==================== Helper Methods ====================

  private TotalExpectationResponse createTestResponse() {
    return TotalExpectationResponse.builder()
        .userIgn("TestUser123")
        .totalCost(530000000000L)
        .totalCostText("5,300억")
        .items(
            List.of(
                TotalExpectationResponse.ItemExpectation.builder()
                    .part("모자")
                    .itemName("에테르넬 나이트헬름")
                    .potential("STR 12% | 9% | 9%")
                    .expectedCost(80000000000L)
                    .expectedCostText("800억")
                    .expectedCount(1500L)
                    .build()))
        .build();
  }

  /**
   * LogicExecutor Pass-through mock (실제 작업 실행)
   *
   * <p>TieredCacheTest 패턴 참조
   */
  @SuppressWarnings("unchecked")
  private LogicExecutor createPassThroughExecutor() {
    LogicExecutor mockExecutor = mock(LogicExecutor.class);

    // execute
    given(mockExecutor.execute(any(ThrowingSupplier.class), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              return task.get();
            });

    // executeVoid
    doAnswer(
            invocation -> {
              ThrowingRunnable task = invocation.getArgument(0);
              task.run();
              return null;
            })
        .when(mockExecutor)
        .executeVoid(any(ThrowingRunnable.class), any(TaskContext.class));

    // executeOrCatch (TotalExpectationCacheService에서 사용)
    given(
            mockExecutor.executeOrCatch(
                any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
        .willAnswer(
            invocation -> {
              ThrowingSupplier<?> task = invocation.getArgument(0);
              Function<Throwable, ?> recovery = invocation.getArgument(1);
              try {
                return task.get();
              } catch (Throwable e) {
                return recovery.apply(e);
              }
            });

    return mockExecutor;
  }
}
