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
import java.util.Set;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * RedisLikeRelationBuffer 단위 테스트 (#271 V5 Stateless Architecture)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Yellow (QA): Mock 기반 단위 테스트로 빠른 피드백
 *   <li>Purple (Auditor): SADD 원자성, 중복 검사 검증
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisLikeRelationBuffer 단위 테스트")
class RedisLikeRelationBufferTest {

  @Mock private RedissonClient redissonClient;

  @Mock private LogicExecutor executor;

  @Mock private RSet<String> relationSet;

  @Mock private RSet<String> pendingSet;

  @Mock private RScript rScript;

  private SimpleMeterRegistry meterRegistry;
  private RedisLikeRelationBuffer buffer;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();

    // Redisson Mocks
    setupRedissonMocks();

    // LogicExecutor Mocks
    setupExecutorMocks();

    buffer = new RedisLikeRelationBuffer(redissonClient, executor, meterRegistry);
  }

  @SuppressWarnings("unchecked")
  private void setupRedissonMocks() {
    lenient()
        .when(redissonClient.<String>getSet(RedisKey.LIKE_RELATIONS.getKey()))
        .thenReturn(relationSet);
    lenient()
        .when(redissonClient.<String>getSet(RedisKey.LIKE_RELATIONS_PENDING.getKey()))
        .thenReturn(pendingSet);
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
  @DisplayName("addRelation() 테스트")
  class AddRelationTest {

    @Test
    @DisplayName("신규 관계 추가 - true 반환")
    void addRelation_shouldReturnTrueForNewRelation() {
      // Given
      when(relationSet.add("fp123:ocid456")).thenReturn(true);
      when(pendingSet.add("fp123:ocid456")).thenReturn(true);

      // When
      Boolean result = buffer.addRelation("fp123", "ocid456");

      // Then
      assertThat(result).isTrue();
      verify(relationSet).add("fp123:ocid456");
      verify(pendingSet).add("fp123:ocid456");
    }

    @Test
    @DisplayName("중복 관계 - false 반환")
    void addRelation_shouldReturnFalseForDuplicate() {
      // Given
      when(relationSet.add("fp123:ocid456")).thenReturn(false);

      // When
      Boolean result = buffer.addRelation("fp123", "ocid456");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("exists() 테스트")
  class ExistsTest {

    @Test
    @DisplayName("존재하는 관계 - true 반환")
    void exists_shouldReturnTrueWhenExists() {
      // Given
      when(relationSet.contains("fp123:ocid456")).thenReturn(true);

      // When
      Boolean result = buffer.exists("fp123", "ocid456");

      // Then
      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("존재하지 않는 관계 - false 반환")
    void exists_shouldReturnFalseWhenNotExists() {
      // Given
      when(relationSet.contains("fp123:ocid456")).thenReturn(false);

      // When
      Boolean result = buffer.exists("fp123", "ocid456");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("fetchAndRemovePending() 테스트")
  class FetchAndRemovePendingTest {

    @Test
    @DisplayName("정상 수행 - Set 반환")
    void fetchAndRemovePending_shouldReturnSet() {
      // Given
      List<String> rawResult = List.of("fp1:ocid1", "fp2:ocid2");
      when(rScript.scriptLoad(anyString())).thenReturn("mock-sha");
      when(rScript.evalSha(
              any(RScript.Mode.class),
              anyString(),
              any(RScript.ReturnType.class),
              anyList(),
              anyString()))
          .thenReturn(rawResult);

      // When
      Set<String> result = buffer.fetchAndRemovePending(10);

      // Then
      assertThat(result).containsExactlyInAnyOrder("fp1:ocid1", "fp2:ocid2");
    }

    @Test
    @DisplayName("빈 Pending - 빈 Set 반환")
    void fetchAndRemovePending_shouldReturnEmptySetWhenEmpty() {
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
      Set<String> result = buffer.fetchAndRemovePending(10);

      // Then
      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("removeRelation() 테스트")
  class RemoveRelationTest {

    @Test
    @DisplayName("존재하는 관계 삭제 - true 반환")
    void removeRelation_shouldReturnTrueWhenRemoved() {
      // Given
      when(relationSet.remove("fp123:ocid456")).thenReturn(true);
      when(pendingSet.remove("fp123:ocid456")).thenReturn(true);

      // When
      Boolean result = buffer.removeRelation("fp123", "ocid456");

      // Then
      assertThat(result).isTrue();
      verify(pendingSet).remove("fp123:ocid456");
    }

    @Test
    @DisplayName("존재하지 않는 관계 - false 반환")
    void removeRelation_shouldReturnFalseWhenNotExists() {
      // Given
      when(relationSet.remove("fp123:ocid456")).thenReturn(false);

      // When
      Boolean result = buffer.removeRelation("fp123", "ocid456");

      // Then
      assertThat(result).isFalse();
    }
  }

  @Nested
  @DisplayName("buildRelationKey() / parseRelationKey() 테스트")
  class KeyTest {

    @Test
    @DisplayName("관계 키 생성")
    void buildRelationKey_shouldCreateCorrectFormat() {
      // When
      String key = buffer.buildRelationKey("fingerprint", "ocid123");

      // Then
      assertThat(key).isEqualTo("fingerprint:ocid123");
    }

    @Test
    @DisplayName("관계 키 파싱")
    void parseRelationKey_shouldExtractParts() {
      // When
      String[] parts = buffer.parseRelationKey("fingerprint:ocid123");

      // Then
      assertThat(parts).containsExactly("fingerprint", "ocid123");
    }

    @Test
    @DisplayName("콜론이 포함된 OCID 파싱")
    void parseRelationKey_shouldHandleColonInOcid() {
      // When
      String[] parts = buffer.parseRelationKey("fingerprint:ocid:with:colons");

      // Then
      assertThat(parts).containsExactly("fingerprint", "ocid:with:colons");
    }
  }

  @Nested
  @DisplayName("사이즈 조회 테스트")
  class SizeTest {

    @Test
    @DisplayName("전체 관계 수 조회")
    void getRelationsSize_shouldReturnSize() {
      // Given
      when(relationSet.size()).thenReturn(100);

      // When
      int result = buffer.getRelationsSize();

      // Then
      assertThat(result).isEqualTo(100);
    }

    @Test
    @DisplayName("Pending 수 조회")
    void getPendingSize_shouldReturnSize() {
      // Given
      when(pendingSet.size()).thenReturn(50);

      // When
      int result = buffer.getPendingSize();

      // Then
      assertThat(result).isEqualTo(50);
    }
  }

  @Nested
  @DisplayName("키 구조 테스트")
  class KeyStructureTest {

    @Test
    @DisplayName("Relations 키 - Hash Tag 패턴 확인")
    void getRelationsKey_shouldUseHashTag() {
      // When
      String key = buffer.getRelationsKey();

      // Then
      assertThat(key).isEqualTo(RedisKey.LIKE_RELATIONS.getKey());
      assertThat(key).contains("{likes}");
    }

    @Test
    @DisplayName("Pending 키 - Hash Tag 패턴 확인")
    void getPendingKey_shouldUseHashTag() {
      // When
      String key = buffer.getPendingKey();

      // Then
      assertThat(key).isEqualTo(RedisKey.LIKE_RELATIONS_PENDING.getKey());
      assertThat(key).contains("{likes}");
    }
  }
}
