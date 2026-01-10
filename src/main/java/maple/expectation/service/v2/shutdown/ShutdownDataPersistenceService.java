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

/**
 * 🍁 셧다운 데이터 영속화 서비스
 * LogicExecutor를 사용하여 try-catch 없이 선언적으로 파일 IO 및 복구 로직을 수행합니다.
 */
@Slf4j
@Service
public class ShutdownDataPersistenceService {

    private final ObjectMapper objectMapper;
    private final LogicExecutor executor;

    @Value("${app.shutdown.backup-directory:/tmp/maple-shutdown}")
    private String backupDirectory;

    @Value("${app.shutdown.archive-directory:/tmp/maple-shutdown/processed}")
    private String archiveDirectory;

    @Getter
    private final String instanceId;

    public ShutdownDataPersistenceService(ObjectMapper objectMapper, LogicExecutor executor) {
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.instanceId = resolveInstanceId();
    }

    @PostConstruct
    public void init() {
        executor.executeWithTranslation(() -> {
            Files.createDirectories(Paths.get(backupDirectory));
            Files.createDirectories(Paths.get(archiveDirectory));
            log.info("✅ [Shutdown Persistence] 디렉토리 초기화 완료 (ID: {})", instanceId);
            return null;
        }, ExceptionTranslator.forFileIO(), TaskContext.of("Persistence", "Init"));
    }

    /**
     * 데이터를 JSON 파일로 원자적(Atomic)으로 저장합니다.
     */
    public Path saveShutdownData(ShutdownData data) {
        if (data == null || data.isEmpty()) return null;

        TaskContext context = TaskContext.of("Persistence", "SaveData");
        Path backupPath = Paths.get(backupDirectory);
        Path targetFile = backupPath.resolve(generateFilename());

        return executor.execute(() -> {
            Path tempFile = Files.createTempFile(backupPath, "shutdown-", ".tmp");

            return executor.executeWithFinally(
                    () -> performAtomicWrite(data, tempFile, targetFile, context),
                    () -> cleanupTempFile(tempFile),
                    context
            );
        }, context);
    }

    /**
     * 기존 백업 파일에 '좋아요' 데이터를 병합하여 저장합니다.
     *
     * <p>P1 Fix: 고정 파일명 + 원자적 교체로 중복 파일 생성 방지</p>
     */
    public void appendLikeEntry(String userIgn, long count) {
        TaskContext context = TaskContext.of("Persistence", "AppendLike", userIgn);

        executor.executeVoid(() -> {
            // P1 Fix: 현재 인스턴스의 백업 파일만 로드 (고정 파일명)
            ShutdownData existingData = loadCurrentInstanceBackup();

            Map<String, Long> mergedBuffer = new HashMap<>(
                    existingData.likeBuffer() != null ? existingData.likeBuffer() : Map.of()
            );
            mergedBuffer.merge(userIgn, count, Long::sum);

            ShutdownData newData = new ShutdownData(
                    LocalDateTime.now(), instanceId, mergedBuffer, existingData.equipmentPending()
            );

            // 원자적 교체: 기존 파일이 있으면 자동으로 덮어쓰기
            saveShutdownData(newData);
        }, context);
    }

    /**
     * P1 Fix: 실패 항목만 저장 (부분 복구 시 사용)
     *
     * <p>복구 중 일부 항목만 실패한 경우, 실패 항목만 새 백업 파일로 저장합니다.</p>
     *
     * @param failedLikes 실패한 좋아요 항목들
     * @param pendingEquipment 기존 장비 대기 목록 (그대로 보존)
     */
    public void saveFailedEntriesOnly(Map<String, Long> failedLikes, List<String> pendingEquipment) {
        if (failedLikes == null || failedLikes.isEmpty()) return;

        ShutdownData failedData = new ShutdownData(
                LocalDateTime.now(),
                instanceId,
                failedLikes,
                pendingEquipment
        );

        executor.executeVoid(() -> {
            Path saved = saveShutdownData(failedData);
            if (saved != null) {
                log.warn("💾 [Persistence] 실패 항목 백업 완료: {} 항목 → {}",
                        failedLikes.size(), saved.getFileName());
            }
        }, TaskContext.of("Persistence", "SaveFailedOnly", "count:" + failedLikes.size()));
    }

    /**
     * 처리되지 않은 장비 목록을 백업 파일에 추가합니다.
     *
     * <p>P1 Fix: 고정 파일명 + 원자적 교체로 중복 파일 생성 방지</p>
     */
    public void savePendingEquipment(List<String> ocids) {
        if (ocids == null || ocids.isEmpty()) return;

        executor.executeVoid(() -> {
            // P1 Fix: 현재 인스턴스의 백업 파일만 로드 (고정 파일명)
            ShutdownData existingData = loadCurrentInstanceBackup();

            List<String> mergedEquipment = new ArrayList<>(
                    existingData.equipmentPending() != null ? existingData.equipmentPending() : List.of()
            );
            mergedEquipment.addAll(ocids);

            ShutdownData newData = new ShutdownData(
                    LocalDateTime.now(), instanceId, existingData.likeBuffer(), mergedEquipment
            );

            // 원자적 교체: 기존 파일이 있으면 자동으로 덮어쓰기
            if (saveShutdownData(newData) != null) {
                log.warn("💾 [Persistence] Equipment 목록 업데이트 완료: {}건", ocids.size());
            }
        }, TaskContext.of("Persistence", "SavePending", "size:" + ocids.size()));
    }

    /**
     * 백업 디렉토리 내의 모든 JSON 파일을 생성 시간 역순으로 조회합니다.
     */
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

    /**
     * 🚀 [수정] executeWithRecovery를 사용하여 파일 손상 시 Optional.empty를 반환합니다.
     * (try-catch 없이 JSON 파싱 에러를 우아하게 처리)
     */
    public Optional<ShutdownData> readBackupFile(Path filePath) {
        return executor.executeOrCatch(
                // 1. 시도: 파일 읽기 및 역직렬화
                () -> Optional.of(objectMapper.readValue(Files.readString(filePath), ShutdownData.class)),

                // 2. 복구: 에러 발생 시(파일 손상 등) 비어있는 Optional 반환
                (e) -> {
                    log.error("⚠️ 파일 손상으로 읽기 실패: {} | 사유: {}", filePath, e.getMessage());
                    return Optional.empty();
                },

                TaskContext.of("Persistence", "ReadFile", filePath.getFileName().toString())
        );
    }

    /**
     * 처리가 완료된 파일을 아카이브 디렉토리로 이동시킵니다.
     */
    public void archiveFile(Path filePath) {
        executor.executeWithTranslation(() -> {
            Files.move(filePath, Paths.get(archiveDirectory).resolve(filePath.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            log.info("📦 [Persistence] 아카이브 완료: {}", filePath.getFileName());
            return null;
        }, ExceptionTranslator.forFileIO(), TaskContext.of("Persistence", "Archive", filePath.getFileName().toString()));
    }

    public Path getBackupDirectory() {
        return Paths.get(backupDirectory);
    }

    public Path getArchiveDirectory() {
        return Paths.get(archiveDirectory);
    }

    // --- Private Helper Methods (모두 LogicExecutor 활용) ---

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

    /**
     * P1 Fix: 현재 인스턴스의 고정 백업 파일 로드
     */
    private ShutdownData loadCurrentInstanceBackup() {
        Path backupFile = Paths.get(backupDirectory).resolve(generateFilename());
        if (!Files.exists(backupFile)) {
            return ShutdownData.empty(instanceId);
        }
        return readBackupFile(backupFile).orElse(ShutdownData.empty(instanceId));
    }

    private LocalDateTime getFileCreationTime(Path path) {
        return executor.executeOrDefault(() -> LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault()),
                LocalDateTime.MIN, TaskContext.of("Persistence", "GetFileTime"));
    }

    /**
     * P1 Fix: 인스턴스당 고정 파일명 사용 (중복 백업 파일 방지)
     *
     * <p>변경 전: shutdown-{timestamp}-{uuid}.json → 다중 파일 생성 가능</p>
     * <p>변경 후: shutdown-{instanceId}.json → 원자적 교체로 단일 파일 유지</p>
     */
    private String generateFilename() {
        return String.format("shutdown-%s.json", instanceId);
    }

    private String resolveInstanceId() {
        return executor.executeOrDefault(
                () -> InetAddress.getLocalHost().getHostName(),
                UUID.randomUUID().toString(),
                TaskContext.of("Persistence", "ResolveInstanceId")
        );
    }
}