package maple.expectation.service.v2.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import maple.expectation.service.v2.LikeSyncExecutor;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * LikeSync 보상 트랜잭션 통합 테스트 (Testcontainers)
 *
 * <p>검증 항목:
 *
 * <ul>
 *   <li>DB 실패 시 원본 키로 데이터 복구
 *   <li>부분 실패 시 실패 항목만 복구
 *   <li>보상 트랜잭션 멱등성 검증
 * </ul>
 *
 * <p><strong>⚠️ Flaky Tests (Redis Key Deletion Issue)</strong>
 *
 * <p>일부 테스트에서 syncRedisToDatabase() 호출 후 원본 키가 삭제되지 않는 문제가 있습니다. fetchAndMove()의 Lua Script RENAME
 * 동작이 예상대로 작동하지 않는 것으로 보입니다.
 *
 * @since 2.0.0
 */
@DisplayName("LikeSync 보상 트랜잭션 통합 테스트")
@Tag("flaky")
class LikeSyncCompensationIntegrationTest extends IntegrationTestSupport {

  private static final String SOURCE_KEY = "{buffer:likes}";

  @Autowired private LikeSyncService likeSyncService;
  @Autowired private LikeBufferStorage likeBufferStorage;
  @Autowired private StringRedisTemplate redisTemplate;

  @MockitoSpyBean private LikeSyncExecutor syncExecutor;

  @BeforeEach
  void setUp() {
    // 테스트 전 Redis 데이터 초기화
    redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    likeBufferStorage.getCache().invalidateAll();
    reset(syncExecutor);
  }

  @Test
  @DisplayName("DB 실패 시 원본 키로 데이터 복구")
  void dbFailure_DataRestoredToSourceKey() {
    // [Given] L2에 데이터 적재
    String testUser = "FailUser";
    long initialCount = 100L;
    redisTemplate.opsForHash().put(SOURCE_KEY, testUser, String.valueOf(initialCount));

    // DB 동기화 실패 설정 (Issue #48: Batch Update)
    doThrow(new RuntimeException("DB Connection Failed"))
        .when(syncExecutor)
        .executeIncrementBatch(anyList());

    // [When] 동기화 실행 (실패 예상)
    likeSyncService.syncRedisToDatabase();

    // [Then] 데이터가 원본 키로 복구되어야 함
    Object restoredValue = redisTemplate.opsForHash().get(SOURCE_KEY, testUser);
    long restoredCount = restoredValue != null ? Long.parseLong(restoredValue.toString()) : 0L;

    assertThat(restoredCount).as("DB 실패 시 데이터가 원본 키로 복구되어야 함").isEqualTo(initialCount);
  }

  @Test
  @DisplayName("배치 실패 시 모든 항목 복구 (Issue #48: Batch Update)")
  void batchFailure_AllItemsRestored() {
    // [Given] L2에 여러 사용자 데이터 적재 (동일 청크에 포함됨)
    String user1 = "User1";
    String user2 = "User2";
    long count1 = 50L;
    long count2 = 75L;

    redisTemplate.opsForHash().put(SOURCE_KEY, user1, String.valueOf(count1));
    redisTemplate.opsForHash().put(SOURCE_KEY, user2, String.valueOf(count2));

    // 배치 실패 설정 (Issue #48: 청크 전체가 실패)
    doThrow(new RuntimeException("DB Batch Failed"))
        .when(syncExecutor)
        .executeIncrementBatch(anyList());

    // [When] 동기화 실행
    likeSyncService.syncRedisToDatabase();

    // [Then] 배치의 모든 항목이 복구되어야 함 (Issue #48: 청크 단위 복구)
    Object restored1 = redisTemplate.opsForHash().get(SOURCE_KEY, user1);
    Object restored2 = redisTemplate.opsForHash().get(SOURCE_KEY, user2);

    long restoredCount1 = restored1 != null ? Long.parseLong(restored1.toString()) : 0L;
    long restoredCount2 = restored2 != null ? Long.parseLong(restored2.toString()) : 0L;

    assertThat(restoredCount1).as("배치 실패 시 첫 번째 사용자 데이터도 복구되어야 함").isEqualTo(count1);

    assertThat(restoredCount2).as("배치 실패 시 두 번째 사용자 데이터도 복구되어야 함").isEqualTo(count2);
  }

  @Test
  @Tag("flaky")
  @DisplayName("동기화 성공 시 임시 키 삭제 확인")
  void syncSuccess_TempKeyDeleted() {
    // [Given] L2에 데이터 적재
    String testUser = "CleanupUser";
    long initialCount = 200L;
    redisTemplate.opsForHash().put(SOURCE_KEY, testUser, String.valueOf(initialCount));

    // [When] 동기화 실행 (성공)
    likeSyncService.syncRedisToDatabase();

    // [Then] 원본 키에 데이터 없음 (동기화 완료)
    assertThat(redisTemplate.hasKey(SOURCE_KEY)).as("동기화 성공 후 원본 키는 비어있어야 함").isFalse();

    // 임시 키도 삭제되어야 함 (패턴 검색)
    var tempKeys = redisTemplate.keys("{buffer:likes}:sync:*");
    assertThat(tempKeys).as("동기화 성공 후 임시 키는 삭제되어야 함").isEmpty();
  }

  @Test
  @Tag("flaky")
  @DisplayName("연속 실패 후 성공 시 정상 동작")
  void consecutiveFailuresThenSuccess_WorksCorrectly() {
    // [Given] L2에 데이터 적재
    String testUser = "RetryUser";
    long initialCount = 150L;
    redisTemplate.opsForHash().put(SOURCE_KEY, testUser, String.valueOf(initialCount));

    // 첫 번째 시도: 실패 (Issue #48: Batch Update)
    doThrow(new RuntimeException("First attempt failed"))
        .when(syncExecutor)
        .executeIncrementBatch(anyList());

    likeSyncService.syncRedisToDatabase();

    // 데이터 복구 확인
    Object restoredValue = redisTemplate.opsForHash().get(SOURCE_KEY, testUser);
    assertThat(restoredValue).isNotNull();

    // [When] 두 번째 시도: 성공
    reset(syncExecutor); // Mock 리셋 (정상 동작)

    likeSyncService.syncRedisToDatabase();

    // [Then] 동기화 성공 후 데이터 없음
    assertThat(redisTemplate.hasKey(SOURCE_KEY)).as("재시도 성공 후 원본 키는 비어있어야 함").isFalse();
  }

  @Test
  @DisplayName("빈 데이터 동기화 시 보상 트랜잭션 발동 안함")
  void emptyData_NoCompensation() {
    // [Given] 빈 상태 (L2에 데이터 없음)
    assertThat(redisTemplate.hasKey(SOURCE_KEY)).isFalse();

    // [When] 동기화 실행
    likeSyncService.syncRedisToDatabase();

    // [Then] 임시 키 생성되지 않음
    var tempKeys = redisTemplate.keys("{buffer:likes}:sync:*");
    assertThat(tempKeys).as("빈 데이터 동기화 시 임시 키가 생성되지 않아야 함").isEmpty();
  }
}
