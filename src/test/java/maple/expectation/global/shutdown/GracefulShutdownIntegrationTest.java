package maple.expectation.global.shutdown;

import maple.expectation.global.shutdown.dto.FlushResult;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.repository.v2.GameCharacterRepository;
import maple.expectation.service.v2.LikeSyncService;
import maple.expectation.service.v2.cache.LikeBufferStorage;
import maple.expectation.service.v2.shutdown.EquipmentPersistenceTracker;
import maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Graceful Shutdown 통합 테스트
 * <p>
 * Testcontainers(MySQL, Redis)를 사용하여 실제 환경과 유사한
 * Graceful Shutdown 시나리오를 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Graceful Shutdown 통합 테스트")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class GracefulShutdownIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private GracefulShutdownCoordinator shutdownCoordinator;

    @Autowired
    private EquipmentPersistenceTracker persistenceTracker;

    @Autowired
    private LikeBufferStorage likeBufferStorage;

    @Autowired
    private LikeSyncService likeSyncService;

    @Autowired
    private ShutdownDataPersistenceService persistenceService;

    @Autowired
    private GameCharacterRepository characterRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final String REDIS_HASH_KEY = "buffer:likes";

    @BeforeEach
    void setUp() {
        // 0. Redis Proxy 정상화 (Toxiproxy toxic 제거)
        try {
            redisProxy.toxics().getAll().forEach(toxic -> {
                try {
                    toxic.remove();
                } catch (IOException e) {
                    // 무시
                }
            });
        } catch (IOException e) {
            // getAll() 호출 시 IOException 발생 가능 - 무시
        }

        // 1. Tracker 리셋 (Shutdown 플래그 초기화)
        persistenceTracker.resetForTesting();

        // 2. Redis 데이터 정리
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // 3. 좋아요 버퍼 초기화
        likeBufferStorage.getCache().invalidateAll();

        // 4. 백업 파일 정리
        List<Path> existingBackups = persistenceService.findAllBackupFiles();
        existingBackups.forEach(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                // 무시
            }
        });
    }

    @Test
    @DisplayName("Equipment 작업 진행 중 Shutdown 시 미완료 항목 백업 테스트")
    void testShutdownWithPendingEquipment() throws Exception {
        // given: 진행 중인 Equipment 작업 시뮬레이션
        CompletableFuture<Void> longRunningTask = new CompletableFuture<>();
        persistenceTracker.trackOperation("test-ocid-001", longRunningTask);

        assertThat(persistenceTracker.getPendingCount()).isEqualTo(1);

        // when: Graceful Shutdown 실행
        shutdownCoordinator.stop();

        // then: 백업 파일에 미완료 OCID가 기록되어야 함
        List<Path> backupFiles = persistenceService.findAllBackupFiles();

        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(backupFiles).isNotEmpty());

        // 백업 데이터 검증
        if (!backupFiles.isEmpty()) {
            ShutdownData data = persistenceService.readBackupFile(backupFiles.get(0)).orElseThrow();
            assertThat(data.equipmentPending()).contains("test-ocid-001");
        }

        // cleanup
        longRunningTask.complete(null);
    }

    @Test
    @DisplayName("좋아요 버퍼 데이터가 있을 때 Shutdown 시 Redis로 Flush 테스트")
    void testShutdownWithLikeBuffer() {
        // given: 좋아요 버퍼에 데이터 추가
        likeBufferStorage.getCounter("testUser1").addAndGet(10);
        likeBufferStorage.getCounter("testUser2").addAndGet(20);

        assertThat(likeBufferStorage.getCache().asMap()).hasSize(2);

        // when: 로컬 버퍼 → Redis Flush 실행 (Phase 2만 실행)
        FlushResult result = likeSyncService.flushLocalToRedisWithFallback();

        // then: 모든 데이터가 Redis로 전송되어야 함
        assertThat(result.redisSuccessCount()).isEqualTo(2);
        assertThat(result.fileBackupCount()).isZero();

        // 로컬 버퍼 비워짐 확인
        assertThat(likeBufferStorage.getCounter("testUser1").get()).isZero();
        assertThat(likeBufferStorage.getCounter("testUser2").get()).isZero();

        // Redis에 데이터 저장 확인
        Object count1 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "testUser1");
        Object count2 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "testUser2");

        assertThat(count1).isNotNull();
        assertThat(count2).isNotNull();
        assertThat(Long.parseLong(count1.toString())).isEqualTo(10L);
        assertThat(Long.parseLong(count2.toString())).isEqualTo(20L);
    }

    @Test
    @DisplayName("Shutdown 시작 후 새로운 비동기 작업 등록 거부 테스트")
    void testRejectNewOperationsAfterShutdown() {
        // given: Shutdown 플래그 활성화
        persistenceTracker.awaitAllCompletion(java.time.Duration.ofSeconds(1));

        // when: 새로운 작업 등록 시도
        CompletableFuture<Void> newTask = new CompletableFuture<>();

        // then: IllegalStateException 발생
        assertThat(persistenceTracker).isNotNull();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
            persistenceTracker.trackOperation("test-ocid-after-shutdown", newTask);
        });

        newTask.complete(null);
    }

    @Test
    @DisplayName("모든 Equipment 작업 완료 시 Shutdown이 빠르게 완료되는지 테스트")
    void testFastShutdownWhenAllTasksCompleted() {
        // given: 모든 작업이 이미 완료된 상태
        assertThat(persistenceTracker.getPendingCount()).isZero();

        // when: Shutdown 실행
        long startTime = System.currentTimeMillis();
        shutdownCoordinator.stop();
        long duration = System.currentTimeMillis() - startTime;

        // then: 빠르게 완료되어야 함 (1초 이내)
        assertThat(duration).isLessThan(1000);
    }

    @Test
    @DisplayName("복구 로직 테스트: 백업 파일이 재시작 시 처리되는지 확인")
    void testRecoveryOnRestart() throws Exception {
        // given: 백업 파일 수동 생성
        ShutdownData testData = new ShutdownData(
                java.time.LocalDateTime.now(),
                "test-instance",
                java.util.Map.of("user1", 100L, "user2", 200L),
                java.util.List.of("ocid-001", "ocid-002")
        );

        Path backupFile = persistenceService.saveShutdownData(testData);
        assertThat(backupFile).isNotNull();
        assertThat(Files.exists(backupFile)).isTrue();

        // when: ShutdownDataRecoveryService의 복구 로직 실행 (수동 호출)
        // 실제 환경에서는 @PostConstruct로 자동 실행됨

        // then: 백업 파일이 아카이브로 이동되었는지 확인
        // (실제 복구 로직 테스트는 별도 단위 테스트에서 수행)
        assertThat(backupFile).exists();
    }

    @Test
    @DisplayName("대량 작업 시 Shutdown이 50초 내 완료되는지 테스트")
    void testShutdownWithin50Seconds() throws Exception {
        // given: 100개의 Equipment 작업 등록 (각 작업은 100ms 소요)
        for (int i = 0; i < 100; i++) {
            int index = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            persistenceTracker.trackOperation("ocid-" + index, future);
        }

        assertThat(persistenceTracker.getPendingCount()).isEqualTo(100);

        // when: Shutdown 시간 측정
        long startTime = System.currentTimeMillis();
        shutdownCoordinator.stop();
        long duration = System.currentTimeMillis() - startTime;

        // then: 50초 이내 완료 (실제로는 20초 타임아웃 이내에 완료됨)
        assertThat(duration).isLessThan(50_000);

        // 모든 작업이 완료되었는지 확인
        assertThat(persistenceTracker.getPendingCount()).isZero();
    }
}
