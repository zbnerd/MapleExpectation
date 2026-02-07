package maple.expectation.global.queue.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.queue.RedisKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RMap;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * RedisLikeBufferStorage 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): Mock 기반 단위 테스트로 빠른 피드백
 *   <li>Purple (Auditor): HINCRBY, FetchAndClear 원자성 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisLikeBufferStorage 단위 테스트")
@Execution(ExecutionMode.SAME_THREAD) // CLAUDE.md Section 24: Mock 공유 상태 충돌 방지
class RedisLikeBufferStorageTest {

  @Mock private RedissonClient redissonClient;

  @Mock private LogicExecutor executor;

  @Mock private RMap<String, Long> rMap;

  @Mock private RScript rScript;

  private SimpleMeterRegistry meterRegistry;
  private RedisLikeBufferStorage storage;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // Redisson Mocks
    setupRedissonMocks();

    // LogicExecutor Mocks
    setupExecutorMocks();

    storage = new RedisLikeBufferStorage(redissonClient, executor, meterRegistry);
  }

  @SuppressWarnings("unchecked")
  private void setupRedissonMocks() {
    lenient().when(redissonClient.getMap(anyString())).thenReturn((RMap) rMap);
    lenient().when(redissonClient.getMap(anyString(), any(Codec.class))).thenReturn((RMap) rMap);
    lenient().when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
  }

  @SuppressWarnings("unchecked")
  private void setupExecutorMocks() {
    // executeOrDefault - 람다 직접 실행
    lenient()
        .when(executor.executeOrDefault(any(ThrowingSupplier.class), any(), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Object defaultValue = invocation.getArgument(1);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    return defaultValue;
                  }
                });

    // executeOrCatch - 람다 직접 실행, 예외 시 recovery 실행
    lenient()
        .when(
            executor.executeOrCatch(
                any(ThrowingSupplier.class), any(Function.class), any(TaskContext.class)))
        .thenAnswer(
            (Answer<Object>)
                invocation -> {
                  ThrowingSupplier<?> task = invocation.getArgument(0);
                  Function<Throwable, ?> recovery = invocation.getArgument(1);
                  try {
                    return task.get();
                  } catch (Exception e) {
                    return recovery.apply(e);
                  }
                });
  }

  @Nested
  @DisplayName("increment() 테스트")
  class IncrementTest {

    @Test
    @DisplayName("정상 증분 - 새 값 반환")
    void increment_shouldReturnNewValue() {
      // Given
      when(rMap.addAndGet("user1", 5L)).thenReturn(15L);

      // When
      Long result = storage.increment("user1", 5L);

      // Then
      assertThat(result).isEqualTo(15L);
      verify(rMap).addAndGet("user1", 5L);
    }

    @Test
    @DisplayName("음수 증분 - 좋아요 취소")
    void increment_shouldHandleNegativeValue() {
      // Given
      when(rMap.addAndGet("user1", -3L)).thenReturn(2L);

      // When
      Long result = storage.increment("user1", -3L);

      // Then
      assertThat(result).isEqualTo(2L);
    }
  }

  @Nested
  @DisplayName("get() 테스트")
  class GetTest {

    @Test
    @DisplayName("존재하는 키 - 값 반환")
    void get_shouldReturnValue() {
      // Given
      when(rMap.get("user1")).thenReturn(10L);

      // When
      Long result = storage.get("user1");

      // Then
      assertThat(result).isEqualTo(10L);
    }

    @Test
    @DisplayName("존재하지 않는 키 - 0 반환")
    void get_shouldReturnZeroWhenKeyNotExists() {
      // Given
      when(rMap.get("user1")).thenReturn(null);

      // When
      Long result = storage.get("user1");

      // Then
      assertThat(result).isEqualTo(0L);
    }
  }

  @Nested
  @DisplayName("getAllCounters() 테스트")
  class GetAllCountersTest {

    @Test
    @DisplayName("정상 조회 - 맵 반환")
    void getAllCounters_shouldReturnMap() {
      // Given
      Map<String, Long> expected = Map.of("user1", 10L, "user2", 20L);
      when(rMap.readAllMap()).thenReturn(expected);

      // When
      Map<String, Long> result = storage.getAllCounters();

      // Then
      assertThat(result).containsEntry("user1", 10L).containsEntry("user2", 20L);
    }

    @Test
    @DisplayName("빈 버퍼 - 빈 맵 반환")
    void getAllCounters_shouldReturnEmptyMapWhenEmpty() {
      // Given
      when(rMap.readAllMap()).thenReturn(Map.of());

      // When
      Map<String, Long> result = storage.getAllCounters();

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("fetchAndClear() 테스트")
  class FetchAndClearTest {

    @Test
    @DisplayName("정상 수행 - 데이터 반환 후 삭제")
    void fetchAndClear_shouldReturnAndClear() {
      // Given: Lua Script가 [[field, value], ...] 형태로 반환
      List<List<String>> rawResult = List.of(List.of("user1", "10"), List.of("user2", "20"));
      when(rScript.scriptLoad(anyString())).thenReturn("mock-sha");
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(rawResult);

      // When
      Map<String, Long> result = storage.fetchAndClear(100);

      // Then
      assertThat(result).containsEntry("user1", 10L).containsEntry("user2", 20L);
    }

    @Test
    @DisplayName("빈 버퍼 - 빈 맵 반환")
    void fetchAndClear_shouldReturnEmptyMapWhenEmpty() {
      // Given
      when(rScript.scriptLoad(anyString())).thenReturn("mock-sha");
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(List.of());

      // When
      Map<String, Long> result = storage.fetchAndClear(100);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("remove() 테스트")
  class RemoveTest {

    @Test
    @DisplayName("정상 삭제 - 삭제된 값 반환")
    void remove_shouldReturnRemovedValue() {
      // Given
      when(rMap.remove("user1")).thenReturn(10L);

      // When
      Long result = storage.remove("user1");

      // Then
      assertThat(result).isEqualTo(10L);
    }

    @Test
    @DisplayName("존재하지 않는 키 - null 반환")
    void remove_shouldReturnNullWhenKeyNotExists() {
      // Given
      when(rMap.remove("user1")).thenReturn(null);

      // When
      Long result = storage.remove("user1");

      // Then
      assertThat(result).isNull();
    }
  }

  @Nested
  @DisplayName("getBufferSize() 테스트")
  class GetBufferSizeTest {

    @Test
    @DisplayName("버퍼 크기 반환")
    void getBufferSize_shouldReturnSize() {
      // Given
      when(rMap.size()).thenReturn(5);

      // When
      int result = storage.getBufferSize();

      // Then
      assertThat(result).isEqualTo(5);
    }
  }

  @Nested
  @DisplayName("키 구조 테스트")
  class KeyStructureTest {

    @Test
    @DisplayName("버퍼 키 - Hash Tag 패턴 확인")
    void getBufferKey_shouldUseHashTag() {
      // When
      String key = storage.getBufferKey();

      // Then
      assertThat(key).isEqualTo(RedisKey.LIKE_BUFFER.getKey());
      assertThat(key).contains("{likes}");
    }
  }

  @Nested
  @DisplayName("메트릭 테스트")
  class MetricsTest {

    @Test
    @DisplayName("메트릭 등록 확인")
    void metrics_shouldBeRegistered() {
      // When: 스토리지 생성 시 메트릭이 등록됨
      // getBufferSize 호출로 메트릭 동작 확인
      when(rMap.size()).thenReturn(5);

      int size = storage.getBufferSize();

      // Then
      assertThat(size).isEqualTo(5);
      verify(redissonClient).getMap(anyString(), any(Codec.class));
    }
  }
}
