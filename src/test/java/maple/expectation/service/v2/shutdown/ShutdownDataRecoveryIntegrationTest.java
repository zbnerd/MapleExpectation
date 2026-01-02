package maple.expectation.service.v2.shutdown;

import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shutdown 백업/복구 End-to-End 통합 테스트
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Shutdown 백업/복구 E2E 통합 테스트")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ShutdownDataRecoveryIntegrationTest extends AbstractContainerBaseTest {

    @Autowired private ShutdownDataPersistenceService persistenceService;
    @Autowired private ShutdownDataRecoveryService recoveryService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private LikeBufferStorage likeBufferStorage;
    @Autowired private EquipmentPersistenceTracker persistenceTracker;

    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() throws IOException {
        // 1. Redis Proxy 및 상태 초기화 (동기적 Flush)
        globalProxyReset();
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        // 2. 내부 캐시 및 트래커 리셋
        likeBufferStorage.getCache().invalidateAll();
        persistenceTracker.resetForTesting();

        // 3. 파일 시스템 완전 청소 (try-with-resources 필수)
        cleanDirectory(persistenceService.getArchiveDirectory().getParent()); // 백업 폴더
        cleanDirectory(persistenceService.getArchiveDirectory()); // 아카이브 폴더

        // OS 파일 핸들 안정화를 위한 짧은 대기
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
    }

    /**
     * 안전한 디렉토리 청소 (자원 해제 포함)
     */
    private void cleanDirectory(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.list(path)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    @Test
    @DisplayName("Shutdown 백업 → 재시작 → 자동 복구 검증")
    void testFullShutdownAndRecovery() throws Exception {
        // Step 1: 백업 데이터 생성
        Map<String, Long> likeBuffer = Map.of("user1", 100L);
        ShutdownData backupData = new ShutdownData(LocalDateTime.now(), "inst-1", likeBuffer, List.of());

        Path backupFile = persistenceService.saveShutdownData(backupData);
        assertThat(backupFile).isNotNull();

        // Step 2: 복구 실행
        recoveryService.recoverFromBackup();

        // Step 3: Redis 검증
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Object count = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "user1");
            assertThat(count).isNotNull();
            assertThat(Long.parseLong(count.toString())).isEqualTo(100L);
        });

        // Step 4: 파일 이동 확인
        assertThat(Files.exists(backupFile)).as("원본 파일은 삭제되어야 함").isFalse();
    }

    @Test
    @DisplayName("복합 백업 데이터 복구: 좋아요 + Equipment 미완료")
    void testRecoveryWithMixedData() throws Exception {
        // given
        Map<String, Long> likeBuffer = Map.of("mixedUser1", 50L, "mixedUser2", 150L);
        List<String> pendingOcids = List.of("mixed-ocid-001");
        ShutdownData backupData = new ShutdownData(LocalDateTime.now(), "inst-mixed", likeBuffer, pendingOcids);

        Path backupFile = persistenceService.saveShutdownData(backupData);

        // when
        recoveryService.recoverFromBackup();

        // then
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Object c1 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "mixedUser1");
            Object c2 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "mixedUser2");
            assertThat(c1).isNotNull();
            assertThat(Long.parseLong(c1.toString())).isEqualTo(50L);
            assertThat(Long.parseLong(c2.toString())).isEqualTo(150L);
        });

        assertThat(Files.exists(backupFile)).isFalse();
    }

    @Test
    @DisplayName("여러 백업 파일 순차 복구 시 중복 합산 방지 확인")
    void testMultipleBackupFilesRecovery() throws Exception {
        // [Given] 두 번의 백업 발생 (내부적으로 병합 로직이 있는 appendLikeEntry 활용)
        persistenceService.appendLikeEntry("multiUser", 10L);
        persistenceService.appendLikeEntry("multiUser", 20L); // 총 30L이 되어야 함

        // [When]
        recoveryService.recoverFromBackup();

        // [Then] 이슈 #123 해결 확인: 10 + 30 = 40이 아니라 최종 상태인 30이어야 함
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Object result = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "multiUser");
            assertThat(result).isNotNull();
            assertThat(Long.parseLong(result.toString())).isEqualTo(30L);
        });
    }

    @Test
    @DisplayName("빈 백업 파일 처리 (복구할 데이터 없음)")
    void testEmptyBackupRecovery() throws Exception {
        recoveryService.recoverFromBackup();
        List<Path> backupFiles = persistenceService.findAllBackupFiles();
        assertThat(backupFiles).isEmpty();
    }
}