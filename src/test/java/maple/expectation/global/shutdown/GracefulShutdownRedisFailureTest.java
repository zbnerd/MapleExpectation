package maple.expectation.global.shutdown;

import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Graceful Shutdown - Redis 장애 시나리오 테스트")
class GracefulShutdownRedisFailureTest extends AbstractContainerBaseTest {

    @Autowired private LikeBufferStorage likeBufferStorage;
    @Autowired private LikeSyncService likeSyncService;
    @Autowired private ShutdownDataPersistenceService persistenceService;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() throws IOException {
        // 1. 부모 클래스의 리셋 로직 호출
        globalProxyReset();

        // 2. Redis 연결성 최종 확인 (프록시 복구 후 클라이언트가 붙었는지 확인)
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                return "PONG".equals(redisTemplate.getConnectionFactory().getConnection().ping());
            } catch (Exception e) {
                return false;
            }
        });

        // 3. 데이터 및 캐시 정리
        // 안전한 flush 방식을 사용합니다.
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        likeBufferStorage.getCache().invalidateAll();

        // 4. 백업 파일 정리
        persistenceService.findAllBackupFiles().forEach(path -> {
            try { Files.deleteIfExists(path); } catch (Exception ignored) {}
        });
    }

    @Test
    @DisplayName("Redis 완전 장애: 좋아요 데이터 파일 백업 확인")
    void testRedisCompleteFailure() throws Exception {
        likeBufferStorage.getCounter("user1").addAndGet(100);

        // 1. Redis 차단
        redisProxy.setConnectionCut(true);

        // 2. 실행
        FlushResult result = likeSyncService.flushLocalToRedisWithFallback();

        // 3. 검증
        assertThat(result.fileBackupCount()).isGreaterThan(0);
        List<Path> backups = persistenceService.findAllBackupFiles();
        assertThat(backups).isNotEmpty();
    }

    @Test
    @DisplayName("Redis 장애 복구 후 정상 동작 확인")
    void testRedisRecoveryAfterFailure() throws Exception {
        // 1. 장애 발생 및 1차 시도
        redisProxy.setConnectionCut(true);
        likeBufferStorage.getCounter("user-fail").addAndGet(50);
        likeSyncService.flushLocalToRedisWithFallback();

        // 2. 복구
        redisProxy.setConnectionCut(false);

        // 💡 [중요] Redis 클라이언트가 재연결될 때까지 대기
        await().atMost(5, TimeUnit.SECONDS).until(() -> {
            try {
                return "PONG".equals(redisTemplate.getConnectionFactory().getConnection().ping());
            } catch (Exception e) {
                return false;
            }
        });

        // 3. 2차 시도
        likeBufferStorage.getCounter("user-success").addAndGet(100);
        FlushResult result = likeSyncService.flushLocalToRedisWithFallback();

        // 4. 검증
        assertThat(result.redisSuccessCount()).isGreaterThan(0);
    }
}