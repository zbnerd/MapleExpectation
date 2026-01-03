package maple.expectation.global.shutdown;

import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import maple.expectation.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Graceful Shutdown 통합 테스트")
class GracefulShutdownIntegrationTest extends IntegrationTestSupport {

    @Autowired private GracefulShutdownCoordinator shutdownCoordinator;
    @Autowired private EquipmentPersistenceTracker persistenceTracker;
    @Autowired private LikeBufferStorage likeBufferStorage;
    @Autowired private LikeSyncService likeSyncService;
    @Autowired private ShutdownDataPersistenceService persistenceService;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        persistenceTracker.resetForTesting();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        likeBufferStorage.getCache().invalidateAll();
        persistenceService.findAllBackupFiles().forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) {} });
    }

    @Test
    @DisplayName("Equipment 작업 진행 중 Shutdown 시 미완료 항목 백업 테스트")
    void testShutdownWithPendingEquipment() throws Exception {
        CompletableFuture<Void> task = new CompletableFuture<>();
        persistenceTracker.trackOperation("test-ocid-001", task);
        shutdownCoordinator.stop();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !persistenceService.findAllBackupFiles().isEmpty());

        ShutdownData data = persistenceService.readBackupFile(persistenceService.findAllBackupFiles().get(0)).orElseThrow();
        assertThat(data.equipmentPending()).contains("test-ocid-001");
        task.complete(null);
    }
}