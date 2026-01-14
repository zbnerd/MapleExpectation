package maple.expectation.service.v2.like;

import maple.expectation.service.v2.LikeSyncExecutor;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * LikeSync 보상 트랜잭션 통합 테스트 (Testcontainers)
 *
 * <p>검증 항목:
 * <ul>
 *   <li>DB 실패 시 원본 키로 데이터 복구</li>
 *   <li>부분 실패 시 실패 항목만 복구</li>
 *   <li>보상 트랜잭션 멱등성 검증</li>
 * </ul>
 * </p>
 *
 * @since 2.0.0
 */
@DisplayName("LikeSync 보상 트랜잭션 통합 테스트")
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

        // DB 동기화 실패 설정
        doThrow(new RuntimeException("DB Connection Failed"))
                .when(syncExecutor).executeIncrement(anyString(), anyLong());

        // [When] 동기화 실행 (실패 예상)
        likeSyncService.syncRedisToDatabase();

        // [Then] 데이터가 원본 키로 복구되어야 함
        Object restoredValue = redisTemplate.opsForHash().get(SOURCE_KEY, testUser);
        long restoredCount = restoredValue != null ? Long.parseLong(restoredValue.toString()) : 0L;

        assertThat(restoredCount)
                .as("DB 실패 시 데이터가 원본 키로 복구되어야 함")
                .isEqualTo(initialCount);
    }

    @Test
    @DisplayName("부분 실패 시 실패 항목만 복구")
    void partialFailure_OnlyFailedItemsRestored() {
        // [Given] L2에 여러 사용자 데이터 적재
        String successUser = "SuccessUser";
        String failUser = "FailUser";
        long successCount = 50L;
        long failCount = 75L;

        redisTemplate.opsForHash().put(SOURCE_KEY, successUser, String.valueOf(successCount));
        redisTemplate.opsForHash().put(SOURCE_KEY, failUser, String.valueOf(failCount));

        // failUser만 실패하도록 설정
        doThrow(new RuntimeException("DB Failed for FailUser"))
                .when(syncExecutor).executeIncrement(org.mockito.ArgumentMatchers.eq(failUser), anyLong());

        // [When] 동기화 실행
        likeSyncService.syncRedisToDatabase();

        // [Then] failUser만 복구되어야 함
        Object restoredValue = redisTemplate.opsForHash().get(SOURCE_KEY, failUser);
        long restoredCount = restoredValue != null ? Long.parseLong(restoredValue.toString()) : 0L;

        assertThat(restoredCount)
                .as("실패한 사용자 데이터만 복구되어야 함")
                .isEqualTo(failCount);

        // successUser는 복구되지 않아야 함 (성공했으므로)
        Object successValue = redisTemplate.opsForHash().get(SOURCE_KEY, successUser);
        assertThat(successValue)
                .as("성공한 사용자 데이터는 복구되지 않아야 함")
                .isNull();
    }

    @Test
    @DisplayName("동기화 성공 시 임시 키 삭제 확인")
    void syncSuccess_TempKeyDeleted() {
        // [Given] L2에 데이터 적재
        String testUser = "CleanupUser";
        long initialCount = 200L;
        redisTemplate.opsForHash().put(SOURCE_KEY, testUser, String.valueOf(initialCount));

        // [When] 동기화 실행 (성공)
        likeSyncService.syncRedisToDatabase();

        // [Then] 원본 키에 데이터 없음 (동기화 완료)
        assertThat(redisTemplate.hasKey(SOURCE_KEY))
                .as("동기화 성공 후 원본 키는 비어있어야 함")
                .isFalse();

        // 임시 키도 삭제되어야 함 (패턴 검색)
        var tempKeys = redisTemplate.keys("{buffer:likes}:sync:*");
        assertThat(tempKeys)
                .as("동기화 성공 후 임시 키는 삭제되어야 함")
                .isEmpty();
    }

    @Test
    @DisplayName("연속 실패 후 성공 시 정상 동작")
    void consecutiveFailuresThenSuccess_WorksCorrectly() {
        // [Given] L2에 데이터 적재
        String testUser = "RetryUser";
        long initialCount = 150L;
        redisTemplate.opsForHash().put(SOURCE_KEY, testUser, String.valueOf(initialCount));

        // 첫 번째 시도: 실패
        doThrow(new RuntimeException("First attempt failed"))
                .when(syncExecutor).executeIncrement(anyString(), anyLong());

        likeSyncService.syncRedisToDatabase();

        // 데이터 복구 확인
        Object restoredValue = redisTemplate.opsForHash().get(SOURCE_KEY, testUser);
        assertThat(restoredValue).isNotNull();

        // [When] 두 번째 시도: 성공
        reset(syncExecutor);  // Mock 리셋 (정상 동작)

        likeSyncService.syncRedisToDatabase();

        // [Then] 동기화 성공 후 데이터 없음
        assertThat(redisTemplate.hasKey(SOURCE_KEY))
                .as("재시도 성공 후 원본 키는 비어있어야 함")
                .isFalse();
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
        assertThat(tempKeys)
                .as("빈 데이터 동기화 시 임시 키가 생성되지 않아야 함")
                .isEmpty();
    }
}
