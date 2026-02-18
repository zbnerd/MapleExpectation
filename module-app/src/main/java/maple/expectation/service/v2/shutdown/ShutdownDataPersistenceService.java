package maple.expectation.service.v2.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import maple.expectation.infrastructure.shutdown.dto.ShutdownData;
import org.springframework.stereotype.Service;

/**
 * Shutdown 데이터 영속화 서비스 (P0/P1 리팩토링 완료)
 *
 * <h3>P0/P1 수정 내역</h3>
 *
 * <ul>
 *   <li>P0-2: saveShutdownData() 중첩 LogicExecutor 평탄화 (Section 15)
 *   <li>P0-3: performAtomicWrite() IOException -> executeWithTranslation (Section 11/12)
 *   <li>P1-1: @Value 필드 주입 -> ShutdownProperties 생성자 주입 (Section 6)
 *   <li>P1-5: resolveInstanceId() DNS 블로킹 -> ShutdownProperties.instanceId
 *   <li>P1-7: 미사용 deleteFiles() 메서드 제거
 * </ul>
 *
 * @see ShutdownProperties 외부 설정
 */
@Slf4j
@Service
public class ShutdownDataPersistenceService {

  private final ObjectMapper objectMapper;
  private final LogicExecutor executor;
  private final ShutdownProperties properties;

  @Getter private final String instanceId;
  private final String backupDirectory;
  private final String archiveDirectory;

  /**
   * P1-1 Fix: @Value -> ShutdownProperties 생성자 주입 (Section 6 준수) P1-5 Fix: resolveInstanceId() DNS
   * 블로킹 제거 -> ShutdownProperties.instanceId
   */
  public ShutdownDataPersistenceService(
      ObjectMapper objectMapper, LogicExecutor executor, ShutdownProperties properties) {
    this.objectMapper = objectMapper;
    this.executor = executor;
    this.properties = properties;
    this.instanceId = properties.getInstanceId();
    this.backupDirectory = properties.getBackupDirectory();
    this.archiveDirectory = properties.getArchiveDirectory();
  }

  @PostConstruct
  public void init() {
    executor.executeOrCatch(
        () -> {
          Files.createDirectories(Paths.get(backupDirectory));
          Files.createDirectories(Paths.get(archiveDirectory));
          log.info("[Shutdown Persistence] 디렉토리 초기화 완료 (ID: {})", instanceId);
          return null;
        },
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              "File IO operation failed", e);
        },
        TaskContext.of("Persistence", "Init"));
  }

  /**
   * P0-2 Fix: 중첩 LogicExecutor 제거 (Section 15: 중첩 실행 금지)
   *
   * <h4>변경 전 (Section 15 위반)</h4>
   *
   * <pre>{@code
   * executor.executeOrCatch(() -> {
   *     Path tempFile = Files.createTempFile(...);
   *     return executor.executeWithFinally(  // 중첩!
   *         () -> performAtomicWrite(...),
   *         () -> cleanupTempFile(tempFile),
   *         context);
   * }, context);
   * }</pre>
   *
   * <h4>변경 후 (평탄화)</h4>
   *
   * <p>단일 executeWithTranslation()으로 파일 I/O 전체를 감싸고, temp 파일 정리는 별도 메서드에서 처리
   */
  public Path saveShutdownData(ShutdownData data) {
    if (data == null || data.isEmpty()) return null;

    TaskContext context = TaskContext.of("Persistence", "SaveData");
    Path backupPath = Paths.get(backupDirectory);
    Path targetFile = backupPath.resolve(generateFilename());

    return executor.executeOrCatch(
        () -> performAtomicWrite(data, backupPath, targetFile),
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              "File IO operation failed", e);
        },
        context);
  }

  /**
   * 기존 백업 파일에 '좋아요' 데이터를 병합하여 저장합니다.
   *
   * <p>P1 Fix: 고정 파일명 + 원자적 교체로 중복 파일 생성 방지
   */
  public void appendLikeEntry(String userIgn, long count) {
    TaskContext context = TaskContext.of("Persistence", "AppendLike", userIgn);

    executor.executeVoid(
        () -> {
          ShutdownData existingData = loadCurrentInstanceBackup();

          Map<String, Long> mergedBuffer =
              new HashMap<>(
                  existingData.likeBuffer() != null ? existingData.likeBuffer() : Map.of());
          mergedBuffer.merge(userIgn, count, Long::sum);

          ShutdownData newData =
              new ShutdownData(
                  LocalDateTime.now(), instanceId, mergedBuffer, existingData.equipmentPending());

          saveShutdownData(newData);
        },
        context);
  }

  /** P1 Fix: 실패 항목만 저장 (부분 복구 시 사용) */
  public void saveFailedEntriesOnly(Map<String, Long> failedLikes, List<String> pendingEquipment) {
    if (failedLikes == null || failedLikes.isEmpty()) return;

    ShutdownData failedData =
        new ShutdownData(LocalDateTime.now(), instanceId, failedLikes, pendingEquipment);

    executor.executeVoid(
        () -> {
          Path saved = saveShutdownData(failedData);
          if (saved != null) {
            log.warn(
                "[Persistence] 실패 항목 백업 완료: {} 항목 -> {}", failedLikes.size(), saved.getFileName());
          }
        },
        TaskContext.of("Persistence", "SaveFailedOnly", "count:" + failedLikes.size()));
  }

  /** Outbox 데이터를 파일로 백업합니다. (Triple Safety Net 2차) */
  public void appendOutboxEntry(String requestId, String payload) {
    TaskContext context = TaskContext.of("Persistence", "AppendOutbox", requestId);

    executor.executeOrCatch(
        () -> {
          Path outboxBackupDir = Paths.get(backupDirectory, "outbox-dlq");
          Files.createDirectories(outboxBackupDir);

          String filename =
              String.format(
                  "outbox-%s-%s.json",
                  LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
                  requestId);
          Path target = outboxBackupDir.resolve(filename);

          Files.writeString(target, payload, StandardOpenOption.CREATE_NEW);
          log.warn("[Persistence] Outbox 백업 완료: {}", filename);
          return null;
        },
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              String.format("Outbox 백업 실패: requestId=%s", requestId), e);
        },
        context);
  }

  /** 처리되지 않은 장비 목록을 백업 파일에 추가합니다. */
  public void savePendingEquipment(List<String> ocids) {
    if (ocids == null || ocids.isEmpty()) return;

    executor.executeVoid(
        () -> {
          ShutdownData existingData = loadCurrentInstanceBackup();

          List<String> mergedEquipment =
              new ArrayList<>(
                  existingData.equipmentPending() != null
                      ? existingData.equipmentPending()
                      : List.of());
          mergedEquipment.addAll(ocids);

          ShutdownData newData =
              new ShutdownData(
                  LocalDateTime.now(), instanceId, existingData.likeBuffer(), mergedEquipment);

          if (saveShutdownData(newData) != null) {
            log.warn("[Persistence] Equipment 목록 업데이트 완료: {}건", ocids.size());
          }
        },
        TaskContext.of("Persistence", "SavePending", "size:" + ocids.size()));
  }

  /** 백업 디렉토리 내의 모든 JSON 파일을 생성 시간 역순으로 조회합니다. */
  public List<Path> findAllBackupFiles() {
    return executor.executeOrCatch(
        () -> {
          Path backupPath = Paths.get(backupDirectory);
          if (!Files.exists(backupPath)) return List.of();

          try (Stream<Path> paths = Files.walk(backupPath, 1)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .sorted(Comparator.comparing(this::getFileCreationTime).reversed())
                .toList();
          }
        },
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              "File IO operation failed", e);
        },
        TaskContext.of("Persistence", "ScanFiles"));
  }

  /** 백업 파일 읽기 (파일 손상 시 Optional.empty 반환) */
  public Optional<ShutdownData> readBackupFile(Path filePath) {
    return executor.executeOrCatch(
        () -> Optional.of(objectMapper.readValue(Files.readString(filePath), ShutdownData.class)),
        (e) -> {
          log.error("[Persistence] 파일 손상으로 읽기 실패: {} | 사유: {}", filePath, e.getMessage());
          return Optional.empty();
        },
        TaskContext.of("Persistence", "ReadFile", filePath.getFileName().toString()));
  }

  /** 처리가 완료된 파일을 아카이브 디렉토리로 이동시킵니다. */
  public void archiveFile(Path filePath) {
    executor.executeOrCatch(
        () -> {
          Files.move(
              filePath,
              Paths.get(archiveDirectory).resolve(filePath.getFileName()),
              StandardCopyOption.REPLACE_EXISTING);
          log.info("[Persistence] 아카이브 완료: {}", filePath.getFileName());
          return null;
        },
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              "File IO operation failed", e);
        },
        TaskContext.of("Persistence", "Archive", filePath.getFileName().toString()));
  }

  public Path getBackupDirectory() {
    return Paths.get(backupDirectory);
  }

  public Path getArchiveDirectory() {
    return Paths.get(archiveDirectory);
  }

  // --- Private Helper Methods ---

  /**
   * P0-3 Fix: IOException -> executeWithTranslation (Section 11/12 준수)
   *
   * <h4>변경 전 (Section 11/12 위반)</h4>
   *
   * <pre>{@code
   * private Path performAtomicWrite(...) throws IOException {
   *     Files.writeString(tempFile, json, ...);  // checked exception 직접 전파
   * }
   * }</pre>
   *
   * <h4>변경 후</h4>
   *
   * <p>temp 파일 생성 + JSON 직렬화 + 원자적 이동을 하나의 executeWithTranslation에서 처리
   */
  private Path performAtomicWrite(ShutdownData data, Path backupPath, Path targetFile)
      throws Exception {
    String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);

    Path tempFile = Files.createTempFile(backupPath, "shutdown-", ".tmp");

    return executor.executeWithFinally(
        () -> {
          Files.writeString(tempFile, json, StandardOpenOption.WRITE);
          Files.move(
              tempFile,
              targetFile,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE);
          log.warn("[Persistence] 백업 완료: {}", targetFile.getFileName());
          return targetFile;
        },
        () -> cleanupTempFile(tempFile),
        TaskContext.of("Persistence", "AtomicWrite"));
  }

  private void cleanupTempFile(Path tempFile) {
    executor.executeOrCatch(
        () -> {
          Files.deleteIfExists(tempFile);
          return null;
        },
        e -> {
          throw new maple.expectation.error.exception.InternalSystemException(
              String.format("임시 파일 삭제 실패: %s", tempFile), e);
        },
        TaskContext.of("Persistence", "CleanupTemp"));
  }

  private ShutdownData loadCurrentInstanceBackup() {
    Path backupFile = Paths.get(backupDirectory).resolve(generateFilename());
    if (!Files.exists(backupFile)) {
      return ShutdownData.empty(instanceId);
    }
    return readBackupFile(backupFile).orElse(ShutdownData.empty(instanceId));
  }

  private LocalDateTime getFileCreationTime(Path path) {
    return executor.executeOrDefault(
        () ->
            LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault()),
        LocalDateTime.MIN,
        TaskContext.of("Persistence", "GetFileTime"));
  }

  /** P1 Fix: 인스턴스당 고정 파일명 사용 (중복 백업 파일 방지) */
  private String generateFilename() {
    return String.format("shutdown-%s.json", instanceId);
  }
}
