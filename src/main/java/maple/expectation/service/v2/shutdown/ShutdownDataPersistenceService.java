package maple.expectation.service.v2.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.executor.strategy.ExceptionTranslator;
import maple.expectation.global.shutdown.dto.ShutdownData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Service
public class ShutdownDataPersistenceService {

    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    @Value("${app.shutdown.backup-directory:/tmp/maple-shutdown}")
    private String backupDirectory;

    @Value("${app.shutdown.archive-directory:/tmp/maple-shutdown/processed}")
    private String archiveDirectory;

    /**
     * -- GETTER --
     * 인스턴스 ID (생성자에서 executor를 통해 안전하게 초기화됨)
     */
    @Getter
    private final String instanceId;

    /**
     * ✅ [변경점] 명시적 생성자를 통해 executor 주입 후 instanceId를 초기화합니다.
     */
    public ShutdownDataPersistenceService(ObjectMapper objectMapper, LogicExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.instanceId = resolveInstanceId(); // 이제 executor 사용 가능!
    }

    @PostConstruct
    public void init() {
        executor.executeWithTranslation(() -> {
            Files.createDirectories(Paths.get(backupDirectory));
            Files.createDirectories(Paths.get(archiveDirectory));
            log.info("✅ [Shutdown Persistence] 백업 디렉토리 초기화 완료 (ID: {})", instanceId);
            return null;
        }, ExceptionTranslator.forFileIO(), TaskContext.of("Persistence", "Init"));
    }

    public Path saveShutdownData(ShutdownData data) {
        if (data.isEmpty()) return null;

        TaskContext context = TaskContext.of("Persistence", "SaveData");
        Path backupPath = Paths.get(backupDirectory);
        String filename = generateFilename();
        Path targetFile = backupPath.resolve(filename);

        return executor.execute(() -> {
            Path tempFile = Files.createTempFile(backupPath, "shutdown-", ".tmp");

            return executor.executeWithFinally(
                    () -> performAtomicWrite(data, tempFile, targetFile, context),
                    () -> cleanupTempFile(tempFile),
                    context
            );
        }, context);
    }

    public void appendLikeEntry(String userIgn, long count) {
        TaskContext context = TaskContext.of("Persistence", "AppendLike", userIgn);

        executor.executeVoid(() -> {
            List<Path> oldFiles = findAllBackupFiles();
            ShutdownData existingData = loadLatestFromList(oldFiles);

            Map<String, Long> mergedBuffer = new HashMap<>(existingData.likeBuffer() != null ? existingData.likeBuffer() : Map.of());
            mergedBuffer.merge(userIgn, count, Long::sum);

            ShutdownData newData = new ShutdownData(LocalDateTime.now(), instanceId, mergedBuffer, existingData.equipmentPending());

            Path newFile = saveShutdownData(newData);
            if (newFile != null) {
                deleteFiles(oldFiles);
            }
        }, context);
    }

    public void savePendingEquipment(List<String> ocids) {
        if (ocids == null || ocids.isEmpty()) return;

        executor.executeVoid(() -> {
            List<Path> oldFiles = findAllBackupFiles();
            ShutdownData existingData = loadLatestFromList(oldFiles);

            List<String> mergedEquipment = new ArrayList<>(existingData.equipmentPending() != null ? existingData.equipmentPending() : List.of());
            mergedEquipment.addAll(ocids);

            ShutdownData newData = new ShutdownData(LocalDateTime.now(), instanceId, existingData.likeBuffer(), mergedEquipment);

            Path newFile = saveShutdownData(newData);
            if (newFile != null) {
                deleteFiles(oldFiles);
            }
            log.warn("💾 [Persistence] Equipment 목록 저장 완료: {}건", ocids.size());
        }, TaskContext.of("Persistence", "SavePending", String.valueOf(ocids.size())));
    }

    public List<Path> findAllBackupFiles() {
        return executor.executeWithTranslation(() -> {
            Path backupPath = Paths.get(backupDirectory);
            if (!Files.exists(backupPath)) return List.of();

            try (Stream<Path> paths = Files.walk(backupPath, 1)) {
                return paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(this::getFileCreationTime).reversed())
                        .toList();
            }
        }, ExceptionTranslator.forFileIO(), TaskContext.of("Persistence", "ScanFiles"));
    }

    public Optional<ShutdownData> readBackupFile(Path filePath) {
        return executor.execute(() -> {
            String json = Files.readString(filePath);
            return Optional.of(objectMapper.readValue(json, ShutdownData.class));
        }, TaskContext.of("Persistence", "ReadFile", filePath.getFileName().toString()));
    }

    public void archiveFile(Path filePath) {
        executor.executeWithTranslation(() -> {
            Files.move(filePath, Paths.get(archiveDirectory).resolve(filePath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            log.info("📦 [Persistence] 아카이브 완료: {}", filePath.getFileName());
            return null;
        }, ExceptionTranslator.forFileIO(), TaskContext.of("Persistence", "Archive", filePath.getFileName().toString()));
    }

    private Path performAtomicWrite(ShutdownData data, Path tempFile, Path targetFile, TaskContext context) throws IOException {
        String json = executor.executeWithTranslation(
                () -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                ExceptionTranslator.forJson(), context);

        Files.writeString(tempFile, json, StandardOpenOption.WRITE);
        Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.warn("💾 [Persistence] 백업 완료: {}", targetFile.getFileName());
        return targetFile;
    }

    private void cleanupTempFile(Path tempFile) {
        executor.executeVoid(() -> Files.deleteIfExists(tempFile), TaskContext.of("Persistence", "CleanupTemp"));
    }

    private void deleteFiles(List<Path> files) {
        files.forEach(file -> executor.executeVoid(() -> Files.deleteIfExists(file), TaskContext.of("Persistence", "DeleteOld")));
    }

    private ShutdownData loadLatestFromList(List<Path> files) {
        return files.isEmpty() ? ShutdownData.empty(instanceId) :
                readBackupFile(files.get(0)).orElse(ShutdownData.empty(instanceId));
    }

    private LocalDateTime getFileCreationTime(Path path) {
        return executor.executeOrDefault(() -> LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault()),
                LocalDateTime.MIN, TaskContext.of("Persistence", "GetFileTime"));
    }

    private String generateFilename() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return String.format("shutdown-%s-%s.json", timestamp, UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * ✅ [최종 박멸] static try-catch를 제거하고 executor.executeOrDefault로 평탄화
     */
    private String resolveInstanceId() {
        return executor.executeOrDefault(
                () -> InetAddress.getLocalHost().getHostName(),
                UUID.randomUUID().toString(), // 실패 시 폴백
                TaskContext.of("Persistence", "ResolveInstanceId")
        );
    }
}