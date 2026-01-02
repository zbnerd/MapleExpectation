package maple.expectation.service.v2.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import maple.expectation.global.shutdown.dto.ShutdownData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShutdownDataPersistenceService 테스트
 */
@DisplayName("ShutdownDataPersistenceService 테스트")
class ShutdownDataPersistenceServiceTest {

    @TempDir
    Path tempDir;

    private ShutdownDataPersistenceService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        // record 지원을 위한 설정
        objectMapper.findAndRegisterModules();

        service = new ShutdownDataPersistenceService(objectMapper);

        // 테스트용 디렉토리 설정
        ReflectionTestUtils.setField(service, "backupDirectory", tempDir.toString());
        ReflectionTestUtils.setField(service, "archiveDirectory", tempDir.resolve("processed").toString());

        // init 호출 (디렉토리 생성)
        service.init();
    }

    @Test
    @DisplayName("초기화 시 디렉토리 생성 테스트")
    void testInitCreatesDirectories() {
        // then
        assertThat(Files.exists(tempDir)).isTrue();
        assertThat(Files.exists(tempDir.resolve("processed"))).isTrue();
    }

    @Test
    @DisplayName("ShutdownData 저장 및 읽기 테스트")
    void testSaveAndReadShutdownData() {
        // given
        Map<String, Long> likeBuffer = Map.of("user1", 10L, "user2", 20L);
        List<String> equipmentPending = List.of("ocid1", "ocid2");

        ShutdownData data = new ShutdownData(
                LocalDateTime.now(),
                "test-server",
                likeBuffer,
                equipmentPending
        );

        // when
        Path savedPath = service.saveShutdownData(data);

        // then
        assertThat(savedPath).isNotNull();
        assertThat(Files.exists(savedPath)).isTrue();

        // when - 파일 읽기
        Optional<ShutdownData> loaded = service.readBackupFile(savedPath);

        // then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().instanceId()).isEqualTo("test-server");
        assertThat(loaded.get().likeBuffer()).hasSize(2);
        assertThat(loaded.get().equipmentPending()).hasSize(2);
    }

    @Test
    @DisplayName("빈 데이터 저장 시 null 반환 테스트")
    void testSaveEmptyDataReturnsNull() {
        // given
        ShutdownData emptyData = ShutdownData.empty("test-server");

        // when
        Path result = service.saveShutdownData(emptyData);

        // then
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("appendLikeEntry - 개별 항목 추가 테스트")
    void testAppendLikeEntry() {
        // when
        service.appendLikeEntry("user1", 10L);
        service.appendLikeEntry("user2", 20L);

        // then
        List<Path> backupFiles = service.findAllBackupFiles();
        assertThat(backupFiles).isNotEmpty(); // 파일이 생성됨

        // 가장 최신 파일 확인 (파일이 여러 개 생성될 수 있음)
        Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
        assertThat(loaded).isPresent();
        // 최신 파일에는 병합된 데이터가 있어야 함
        assertThat(loaded.get().likeBuffer()).containsEntry("user1", 10L);
        assertThat(loaded.get().likeBuffer()).containsEntry("user2", 20L);
    }

    @Test
    @DisplayName("appendLikeEntry - 동일 유저 중복 추가 시 합산 테스트")
    void testAppendLikeEntryMerge() {
        // when
        service.appendLikeEntry("user1", 10L);
        service.appendLikeEntry("user1", 5L);

        // then
        List<Path> backupFiles = service.findAllBackupFiles();
        Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));

        assertThat(loaded).isPresent();
        assertThat(loaded.get().likeBuffer()).containsEntry("user1", 15L); // 10 + 5 = 15
    }

    @Test
    @DisplayName("savePendingEquipment - Equipment 목록 저장 테스트")
    void testSavePendingEquipment() {
        // given
        List<String> ocids = List.of("ocid1", "ocid2", "ocid3");

        // when
        service.savePendingEquipment(ocids);

        // then
        List<Path> backupFiles = service.findAllBackupFiles();
        assertThat(backupFiles).hasSize(1);

        Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
        assertThat(loaded).isPresent();
        assertThat(loaded.get().equipmentPending()).hasSize(3);
        assertThat(loaded.get().equipmentPending()).contains("ocid1", "ocid2", "ocid3");
    }

    @Test
    @DisplayName("findAllBackupFiles - 백업 파일 스캔 테스트")
    void testFindAllBackupFiles() throws Exception {
        // given
        ShutdownData data1 = new ShutdownData(
                LocalDateTime.now(),
                "server1",
                Map.of("user1", 10L),
                List.of()
        );

        ShutdownData data2 = new ShutdownData(
                LocalDateTime.now(),
                "server2",
                Map.of("user2", 20L),
                List.of()
        );

        // when
        service.saveShutdownData(data1);
        Thread.sleep(10); // 파일명 시간 구분을 위해 약간의 딜레이
        service.saveShutdownData(data2);

        // then
        List<Path> backupFiles = service.findAllBackupFiles();
        assertThat(backupFiles).hasSize(2);
        assertThat(backupFiles).allMatch(path -> path.toString().endsWith(".json"));
    }

    @Test
    @DisplayName("archiveFile - 파일 아카이브 테스트")
    void testArchiveFile() {
        // given
        ShutdownData data = new ShutdownData(
                LocalDateTime.now(),
                "test-server",
                Map.of("user1", 10L),
                List.of()
        );

        Path savedPath = service.saveShutdownData(data);
        assertThat(Files.exists(savedPath)).isTrue();

        // when
        service.archiveFile(savedPath);

        // then
        assertThat(Files.exists(savedPath)).isFalse(); // 원본 삭제됨
        Path archivedPath = tempDir.resolve("processed").resolve(savedPath.getFileName());
        assertThat(Files.exists(archivedPath)).isTrue(); // 아카이브 디렉토리로 이동
    }

    @Test
    @DisplayName("JSON 직렬화/역직렬화 정확도 테스트")
    void testJsonSerializationAccuracy() {
        // given
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> likeBuffer = Map.of(
                "user1", 100L,
                "user2", 200L,
                "user3", 300L
        );
        List<String> equipmentPending = List.of("ocid1", "ocid2", "ocid3", "ocid4", "ocid5");

        ShutdownData original = new ShutdownData(now, "test-server", likeBuffer, equipmentPending);

        // when
        Path savedPath = service.saveShutdownData(original);
        Optional<ShutdownData> loaded = service.readBackupFile(savedPath);

        // then
        assertThat(loaded).isPresent();
        ShutdownData restored = loaded.get();

        assertThat(restored.instanceId()).isEqualTo(original.instanceId());
        assertThat(restored.likeBuffer()).isEqualTo(original.likeBuffer());
        assertThat(restored.equipmentPending()).isEqualTo(original.equipmentPending());
        // LocalDateTime은 나노초 단위까지 같은지 확인
        assertThat(restored.timestamp()).isEqualToIgnoringNanos(original.timestamp());
    }

    @Test
    @DisplayName("백업 파일이 없을 때 빈 리스트 반환 테스트")
    void testFindAllBackupFilesWhenEmpty() {
        // when
        List<Path> backupFiles = service.findAllBackupFiles();

        // then
        assertThat(backupFiles).isEmpty();
    }

    @Test
    @DisplayName("손상된 JSON 파일 읽기 시 Optional.empty 반환 테스트")
    void testReadCorruptedFile() throws Exception {
        // given
        Path corruptedFile = tempDir.resolve("corrupted.json");
        Files.writeString(corruptedFile, "{ invalid json content }");

        // when
        Optional<ShutdownData> result = service.readBackupFile(corruptedFile);

        // then
        assertThat(result).isEmpty();
    }
}
