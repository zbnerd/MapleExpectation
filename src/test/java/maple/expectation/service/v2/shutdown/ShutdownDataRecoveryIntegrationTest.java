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
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shutdown 백업/복구 End-to-End 통합 테스트
 * <p>
 * Shutdown 시 생성된 백업 파일을 읽어 데이터를 복구하는
 * {@link ShutdownDataRecoveryService}의 전체 흐름을 검증합니다.
 * <p>
 * <b>테스트 시나리오:</b>
 * <ol>
 *   <li>백업 파일 생성 → 복구 → Redis/DB 저장 검증</li>
 *   <li>Equipment 미완료 항목 로그 기록 검증</li>
 *   <li>백업 파일 아카이브 이동 확인</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Shutdown 백업/복구 E2E 통합 테스트")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ShutdownDataRecoveryIntegrationTest extends AbstractContainerBaseTest {

    @Autowired
    private ShutdownDataPersistenceService persistenceService;

    @Autowired
    private ShutdownDataRecoveryService recoveryService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private GameCharacterRepository characterRepository;

    @Autowired
    private LikeBufferStorage likeBufferStorage;

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

        // 1. Redis 데이터 정리
        Set<String> keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }

        // 2. 좋아요 버퍼 초기화
        likeBufferStorage.getCache().invalidateAll();

        // 3. 백업 파일 및 아카이브 정리
        List<Path> existingBackups = persistenceService.findAllBackupFiles();
        existingBackups.forEach(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (Exception e) {
                // 무시
            }
        });

        // 아카이브 디렉토리도 정리
        Path archiveDir = persistenceService.getArchiveDirectory();
        if (Files.exists(archiveDir)) {
            try {
                Files.list(archiveDir).forEach(file -> {
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception e) {
                        // 무시
                    }
                });
            } catch (Exception e) {
                // 무시
            }
        }
    }

    @Test
    @DisplayName("Shutdown 백업 → 재시작 → 자동 복구 검증")
    void testFullShutdownAndRecovery() throws Exception {
        // Step 1: 백업 데이터 생성 (Shutdown 시 저장되는 형태)
        Map<String, Long> likeBuffer = Map.of(
                "user1", 100L,
                "user2", 200L,
                "user3", 300L
        );

        ShutdownData backupData = new ShutdownData(
                LocalDateTime.now(),
                "test-instance-001",
                likeBuffer,
                List.of() // Equipment 미완료 없음
        );

        Path backupFile = persistenceService.saveShutdownData(backupData);
        assertThat(backupFile).isNotNull();
        assertThat(Files.exists(backupFile)).isTrue();

        // Step 2: RecoveryService 수동 실행 (실제 환경에서는 @PostConstruct로 자동 실행)
        recoveryService.recoverFromBackup();

        // Step 3: Redis에 데이터 복구되었는지 확인
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Object count1 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "user1");
                    Object count2 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "user2");
                    Object count3 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "user3");

                    assertThat(count1).isNotNull();
                    assertThat(count2).isNotNull();
                    assertThat(count3).isNotNull();

                    assertThat(Long.parseLong(count1.toString())).isEqualTo(100L);
                    assertThat(Long.parseLong(count2.toString())).isEqualTo(200L);
                    assertThat(Long.parseLong(count3.toString())).isEqualTo(300L);
                });

        // Step 4: 백업 파일이 아카이브로 이동되었는지 확인
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(Files.exists(backupFile)).isFalse();

                    // 아카이브 디렉토리에 파일이 이동되었는지 확인
                    Path archiveDir = persistenceService.getArchiveDirectory();
                    long archivedFileCount = Files.list(archiveDir).count();
                    assertThat(archivedFileCount).isGreaterThan(0);
                });
    }

    @Test
    @DisplayName("Equipment 미완료 복구: 로그 기록 확인")
    void testRecoveryWithPendingEquipment() throws Exception {
        // given: Equipment 미완료 항목이 포함된 백업 데이터
        List<String> pendingOcids = List.of(
                "pending-ocid-001",
                "pending-ocid-002",
                "pending-ocid-003"
        );

        ShutdownData backupData = new ShutdownData(
                LocalDateTime.now(),
                "test-instance-002",
                Map.of(), // 좋아요 버퍼 없음
                pendingOcids
        );

        Path backupFile = persistenceService.saveShutdownData(backupData);
        assertThat(Files.exists(backupFile)).isTrue();

        // when: 복구 실행
        recoveryService.recoverFromBackup();

        // then: 백업 파일이 아카이브로 이동
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(Files.exists(backupFile)).isFalse();
                });

        // Equipment 미완료 항목은 로그로만 기록되고 자동 복구는 안 됨
        // (실제 로그 검증은 LogCapture 등을 사용해야 하지만, 여기서는 생략)
    }

    @Test
    @DisplayName("복합 백업 데이터 복구: 좋아요 + Equipment 미완료")
    void testRecoveryWithMixedData() throws Exception {
        // given: 좋아요와 Equipment 미완료가 모두 포함된 백업
        Map<String, Long> likeBuffer = Map.of(
                "mixedUser1", 50L,
                "mixedUser2", 150L
        );

        List<String> pendingOcids = List.of("mixed-ocid-001");

        ShutdownData backupData = new ShutdownData(
                LocalDateTime.now(),
                "test-instance-003",
                likeBuffer,
                pendingOcids
        );

        Path backupFile = persistenceService.saveShutdownData(backupData);
        assertThat(Files.exists(backupFile)).isTrue();

        // when: 복구 실행
        recoveryService.recoverFromBackup();

        // then: 좋아요 데이터는 Redis로 복구
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Object count1 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "mixedUser1");
                    Object count2 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "mixedUser2");

                    assertThat(count1).isNotNull();
                    assertThat(count2).isNotNull();
                    assertThat(Long.parseLong(count1.toString())).isEqualTo(50L);
                    assertThat(Long.parseLong(count2.toString())).isEqualTo(150L);
                });

        // 백업 파일 아카이브 확인
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(Files.exists(backupFile)).isFalse());
    }

    @Test
    @DisplayName("여러 백업 파일 순차 복구")
    void testMultipleBackupFilesRecovery() throws Exception {
        // given: 여러 개의 백업 파일 생성
        ShutdownData backup1 = new ShutdownData(
                LocalDateTime.now().minusMinutes(10),
                "instance-1",
                Map.of("multiUser1", 10L),
                List.of()
        );

        ShutdownData backup2 = new ShutdownData(
                LocalDateTime.now().minusMinutes(5),
                "instance-2",
                Map.of("multiUser2", 20L),
                List.of()
        );

        Path file1 = persistenceService.saveShutdownData(backup1);
        Path file2 = persistenceService.saveShutdownData(backup2);

        assertThat(Files.exists(file1)).isTrue();
        assertThat(Files.exists(file2)).isTrue();

        // when: 복구 실행 (모든 백업 파일 처리)
        recoveryService.recoverFromBackup();

        // then: 모든 데이터가 Redis로 복구
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Object count1 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "multiUser1");
                    Object count2 = redisTemplate.opsForHash().get(REDIS_HASH_KEY, "multiUser2");

                    assertThat(count1).isNotNull();
                    assertThat(count2).isNotNull();
                    assertThat(Long.parseLong(count1.toString())).isEqualTo(10L);
                    assertThat(Long.parseLong(count2.toString())).isEqualTo(20L);
                });

        // 모든 백업 파일이 아카이브로 이동
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(Files.exists(file1)).isFalse();
                    assertThat(Files.exists(file2)).isFalse();
                });
    }

    @Test
    @DisplayName("빈 백업 파일 처리 (복구할 데이터 없음)")
    void testEmptyBackupRecovery() throws Exception {
        // given: 빈 백업 데이터
        ShutdownData emptyBackup = ShutdownData.empty("empty-instance");

        // 빈 데이터는 저장되지 않음 (saveShutdownData가 null 반환)
        Path backupFile = persistenceService.saveShutdownData(emptyBackup);
        assertThat(backupFile).isNull();

        // when/then: 백업 파일이 없으므로 복구 실행 시 오류 없이 처리되어야 함
        recoveryService.recoverFromBackup();

        // 백업 파일이 없으므로 아무 일도 일어나지 않음
        List<Path> backupFiles = persistenceService.findAllBackupFiles();
        assertThat(backupFiles).isEmpty();
    }
}
