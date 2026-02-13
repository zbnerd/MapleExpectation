package maple.expectation.service.v2.shutdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import maple.expectation.global.common.function.ThrowingSupplier;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.function.ThrowingRunnable;
import maple.expectation.global.shutdown.dto.ShutdownData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** ShutdownDataPersistenceService 테스트 LogicExecutor의 모든 실행 패턴을 모킹하여 실제 로직이 수행되도록 보장합니다. */
@DisplayName("ShutdownDataPersistenceService 테스트")
class ShutdownDataPersistenceServiceTest {

  @TempDir Path tempDir;
  private ShutdownDataPersistenceService service;
  private ObjectMapper objectMapper;
  private LogicExecutor executor;

  @BeforeEach
  void setUp() throws Throwable {
    // Jackson 설정
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.findAndRegisterModules();

    // LogicExecutor Mock 생성
    executor = Mockito.mock(LogicExecutor.class);

    // 🚀 [중요] LogicExecutor의 모든 실행 패턴에 대해 내부 람다를 강제로 실행하도록 설정 (Passthrough)

    // 1. executeWithRecovery: 정상 로직 실행 후 에러 시 복구 로직 실행
    lenient()
        .doAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Function<Throwable, Object> recovery = inv.getArgument(1);
              try {
                return task.get();
              } catch (Throwable e) {
                return recovery.apply(e); // 복구 로직(Optional.empty 등) 수행
              }
            })
        .when(executor)
        .executeOrCatch(any(), any(), any());

    // 2. execute: 단순 실행
    lenient()
        .doAnswer(inv -> ((ThrowingSupplier<?>) inv.getArgument(0)).get())
        .when(executor)
        .execute(any(ThrowingSupplier.class), (TaskContext) any());

    // 3. executeVoid: 리턴 없는 실행
    lenient()
        .doAnswer(
            inv -> {
              ((ThrowingRunnable) inv.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .executeVoid(any(ThrowingRunnable.class), (TaskContext) any());

    // 4. executeWithTranslation: 예외 번역기 버전 실행
    lenient()
        .doAnswer(inv -> ((ThrowingSupplier<?>) inv.getArgument(0)).get())
        .when(executor)
        .executeWithTranslation(any(ThrowingSupplier.class), any(), any());

    // 5. executeWithFinally: finally 블록 보장 실행
    lenient()
        .doAnswer(
            inv -> {
              ThrowingSupplier<?> task = inv.getArgument(0);
              Runnable finalizer = inv.getArgument(1);
              try {
                return task.get();
              } finally {
                finalizer.run();
              }
            })
        .when(executor)
        .executeWithFinally(any(ThrowingSupplier.class), any(Runnable.class), any());

    // 6. executeOrDefault: 실패 시 기본값 반환
    lenient()
        .doAnswer(
            inv -> {
              try {
                return ((ThrowingSupplier<?>) inv.getArgument(0)).get();
              } catch (Throwable e) {
                return inv.getArgument(1);
              }
            })
        .when(executor)
        .executeOrDefault(any(ThrowingSupplier.class), any(), any());

    // 서비스 인스턴스 생성 (P1-1 Fix: ShutdownProperties 생성자 주입)
    ShutdownProperties shutdownProperties = new ShutdownProperties();
    shutdownProperties.setBackupDirectory(tempDir.toString());
    shutdownProperties.setArchiveDirectory(tempDir.resolve("processed").toString());
    shutdownProperties.setInstanceId("test-instance");
    service = new ShutdownDataPersistenceService(objectMapper, executor, shutdownProperties);

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

    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), "test-server", likeBuffer, equipmentPending);

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
    assertThat(backupFiles).isNotEmpty();

    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
    assertThat(loaded).isPresent();
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
    assertThat(loaded.get().likeBuffer()).containsEntry("user1", 15L);
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
  }

  @Test
  @DisplayName("findAllBackupFiles - 백업 파일 스캔 테스트 (고정 파일명으로 원자적 교체)")
  void testFindAllBackupFiles() {
    // given
    ShutdownData data1 =
        new ShutdownData(LocalDateTime.now(), "server1", Map.of("u1", 1L), List.of());
    ShutdownData data2 =
        new ShutdownData(LocalDateTime.now(), "server2", Map.of("u2", 2L), List.of());

    // when
    // CLAUDE.md Section 24: Thread.sleep() 제거 - 동기 저장이므로 지연 불필요
    // 두 번째 저장이 첫 번째를 원자적으로 교체하는 동작은 시간과 무관
    service.saveShutdownData(data1);
    service.saveShutdownData(data2);

    // then - P1 Fix: 고정 파일명 사용으로 인스턴스당 1개 파일만 유지
    // 두 번째 저장이 첫 번째를 원자적으로 교체함
    List<Path> backupFiles = service.findAllBackupFiles();
    assertThat(backupFiles).hasSize(1);
    assertThat(backupFiles).allMatch(path -> path.toString().endsWith(".json"));

    // 최신 데이터(data2)가 저장되어 있어야 함
    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
    assertThat(loaded).isPresent();
    assertThat(loaded.get().likeBuffer()).containsEntry("u2", 2L);
  }

  @Test
  @DisplayName("archiveFile - 파일 아카이브 테스트")
  void testArchiveFile() {
    // given
    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), "test-server", Map.of("u1", 1L), List.of());
    Path savedPath = service.saveShutdownData(data);
    assertThat(Files.exists(savedPath)).isTrue();

    // when
    service.archiveFile(savedPath);

    // then
    assertThat(Files.exists(savedPath)).isFalse();
    Path archivedPath = tempDir.resolve("processed").resolve(savedPath.getFileName());
    assertThat(Files.exists(archivedPath)).isTrue();
  }

  @Test
  @DisplayName("JSON 직렬화/역직렬화 정확도 테스트")
  void testJsonSerializationAccuracy() {
    // given
    LocalDateTime now = LocalDateTime.now();
    ShutdownData original = new ShutdownData(now, "test-server", Map.of("u1", 1L), List.of("o1"));

    // when
    Path savedPath = service.saveShutdownData(original);
    Optional<ShutdownData> loaded = service.readBackupFile(savedPath);

    // then
    assertThat(loaded).isPresent();
    ShutdownData restored = loaded.get();
    assertThat(restored.instanceId()).isEqualTo(original.instanceId());
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
    // 🚀 이제 readBackupFile 내부에서 executeWithRecovery를 사용하여
    // 예외를 잡고 Optional.empty()를 반환하므로 테스트가 성공합니다.
    Optional<ShutdownData> result = service.readBackupFile(corruptedFile);

    // then
    assertThat(result).isEmpty();
  }
}
