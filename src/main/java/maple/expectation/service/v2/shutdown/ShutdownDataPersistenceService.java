package maple.expectation.service.v2.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Shutdown 시 데이터를 로컬 파일로 백업하고 복구하는 서비스
 * <p>
 * Redis/DB 장애 시 데이터 유실을 방지하기 위해 로컬 파일 시스템에
 * JSON 형식으로 데이터를 저장합니다.
 * <p>
 * Atomic write 패턴 (temp + move)을 사용하여 파일 쓰기 중
 * 시스템 장애 발생 시에도 부분 파일이 생성되지 않도록 보장합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShutdownDataPersistenceService {

    private final ObjectMapper objectMapper;

    @Value("${app.shutdown.backup-directory:/tmp/maple-shutdown}")
    private String backupDirectory;

    @Value("${app.shutdown.archive-directory:/tmp/maple-shutdown/processed}")
    private String archiveDirectory;

    private final String instanceId = resolveInstanceId();

    /**
     * 서비스 초기화 시 백업 디렉토리 생성
     */
    @PostConstruct
    public void init() {
        try {
            Path backupPath = Paths.get(backupDirectory);
            Path archivePath = Paths.get(archiveDirectory);

            Files.createDirectories(backupPath);
            Files.createDirectories(archivePath);

            log.info("✅ [Shutdown Persistence] 백업 디렉토리 초기화 완료");
            log.info("   - 백업: {}", backupPath.toAbsolutePath());
            log.info("   - 아카이브: {}", archivePath.toAbsolutePath());
            log.info("   - Instance ID: {}", instanceId);

        } catch (IOException e) {
            log.error("❌ [Shutdown Persistence] 백업 디렉토리 생성 실패", e);
            throw new IllegalStateException("백업 디렉토리를 생성할 수 없습니다", e);
        }
    }

    /**
     * 전체 Shutdown 데이터를 파일로 저장
     * <p>
     * Atomic write 패턴을 사용하여 파일 쓰기 중 장애 발생 시에도
     * 부분 파일이 생성되지 않도록 보장합니다.
     *
     * @param data 저장할 Shutdown 데이터
     * @return 저장된 파일 경로
     */
    public Path saveShutdownData(ShutdownData data) {
        if (data.isEmpty()) {
            log.debug("📝 [Shutdown Persistence] 저장할 데이터 없음");
            return null;
        }

        try {
            Path backupPath = Paths.get(backupDirectory);
            String filename = generateFilename();
            Path targetFile = backupPath.resolve(filename);

            // Atomic write: temp file + move
            Path tempFile = Files.createTempFile(backupPath, "shutdown-", ".tmp");

            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(data);

                Files.writeString(tempFile, json, StandardOpenOption.WRITE);

                // Atomic move
                Files.move(tempFile, targetFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);

                log.warn("💾 [Shutdown Persistence] 백업 파일 저장 완료");
                log.warn("   - 파일: {}", targetFile.getFileName());
                log.warn("   - 항목: {} 개", data.getTotalItems());

                return targetFile;

            } catch (Exception e) {
                // Cleanup temp file on failure
                Files.deleteIfExists(tempFile);
                throw e;
            }

        } catch (Exception e) {
            log.error("❌ [Shutdown Persistence] 백업 파일 저장 실패", e);
            return null;
        }
    }

    /**
     * 개별 좋아요 항목을 파일에 추가
     * <p>
     * 기존 백업 파일이 있으면 병합하고, 없으면 새로 생성합니다.
     *
     * @param userIgn 사용자 IGN
     * @param count   좋아요 수
     */
    public void appendLikeEntry(String userIgn, long count) {
        try {
            // 기존 백업 파일 찾기
            ShutdownData existingData = loadLatestBackup().orElse(
                    ShutdownData.empty(instanceId)
            );

            // 기존 데이터와 병합
            Map<String, Long> mergedLikeBuffer = new HashMap<>(
                    existingData.likeBuffer() != null ? existingData.likeBuffer() : Map.of()
            );
            mergedLikeBuffer.merge(userIgn, count, Long::sum);

            ShutdownData newData = new ShutdownData(
                    LocalDateTime.now(),
                    instanceId,
                    mergedLikeBuffer,
                    existingData.equipmentPending()
            );

            saveShutdownData(newData);

        } catch (Exception e) {
            log.error("❌ [Shutdown Persistence] 좋아요 항목 추가 실패: {}", userIgn, e);
        }
    }

    /**
     * 미완료 Equipment OCID 목록을 파일에 저장
     *
     * @param ocids 미완료 OCID 목록
     */
    public void savePendingEquipment(List<String> ocids) {
        if (ocids == null || ocids.isEmpty()) {
            return;
        }

        try {
            ShutdownData existingData = loadLatestBackup().orElse(
                    ShutdownData.empty(instanceId)
            );

            List<String> mergedEquipment = new ArrayList<>(
                    existingData.equipmentPending() != null ? existingData.equipmentPending() : List.of()
            );
            mergedEquipment.addAll(ocids);

            ShutdownData newData = new ShutdownData(
                    LocalDateTime.now(),
                    instanceId,
                    existingData.likeBuffer(),
                    mergedEquipment
            );

            saveShutdownData(newData);

            log.warn("💾 [Shutdown Persistence] Equipment 목록 저장: {}건", ocids.size());

        } catch (Exception e) {
            log.error("❌ [Shutdown Persistence] Equipment 목록 저장 실패", e);
        }
    }

    /**
     * 백업 디렉토리의 모든 백업 파일을 찾아 반환
     *
     * @return 백업 파일 경로 리스트 (생성 시각 역순)
     */
    public List<Path> findAllBackupFiles() {
        try {
            Path backupPath = Paths.get(backupDirectory);

            if (!Files.exists(backupPath)) {
                return List.of();
            }

            try (Stream<Path> paths = Files.walk(backupPath, 1)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .filter(path -> !path.getFileName().toString().startsWith("."))
                        .sorted(Comparator.comparing(this::getFileCreationTime).reversed())
                        .toList();
            }

        } catch (IOException e) {
            log.error("❌ [Shutdown Persistence] 백업 파일 스캔 실패", e);
            return List.of();
        }
    }

    /**
     * 백업 파일을 읽어서 ShutdownData로 반환
     *
     * @param filePath 백업 파일 경로
     * @return ShutdownData 객체
     */
    public Optional<ShutdownData> readBackupFile(Path filePath) {
        try {
            String json = Files.readString(filePath);
            ShutdownData data = objectMapper.readValue(json, ShutdownData.class);
            return Optional.of(data);

        } catch (IOException e) {
            log.error("❌ [Shutdown Persistence] 백업 파일 읽기 실패: {}", filePath, e);
            return Optional.empty();
        }
    }

    /**
     * 처리 완료된 백업 파일을 아카이브 디렉토리로 이동
     *
     * @param filePath 백업 파일 경로
     */
    public void archiveFile(Path filePath) {
        try {
            Path archivePath = Paths.get(archiveDirectory);
            Path targetPath = archivePath.resolve(filePath.getFileName());

            Files.move(filePath, targetPath,
                    StandardCopyOption.REPLACE_EXISTING);

            log.info("📦 [Shutdown Persistence] 백업 파일 아카이브: {}", filePath.getFileName());

        } catch (IOException e) {
            log.error("❌ [Shutdown Persistence] 파일 아카이브 실패: {}", filePath, e);
        }
    }

    /**
     * 가장 최근 백업 파일을 로드
     */
    private Optional<ShutdownData> loadLatestBackup() {
        List<Path> backupFiles = findAllBackupFiles();
        if (backupFiles.isEmpty()) {
            return Optional.empty();
        }
        return readBackupFile(backupFiles.get(0));
    }

    /**
     * 백업 파일명 생성
     * 패턴: shutdown-{yyyyMMdd-HHmmss}-{uuid}.json
     */
    private String generateFilename() {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return String.format("shutdown-%s-%s.json", timestamp, uuid);
    }

    /**
     * 파일 생성 시각 조회 (정렬용)
     */
    private LocalDateTime getFileCreationTime(Path path) {
        try {
            return LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(),
                    java.time.ZoneId.systemDefault()
            );
        } catch (IOException e) {
            return LocalDateTime.MIN;
        }
    }

    /**
     * 현재 서버 인스턴스 ID를 반환합니다.
     *
     * @return 인스턴스 ID (호스트명 또는 UUID)
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 아카이브 디렉토리 경로를 반환합니다.
     *
     * @return 아카이브 디렉토리 Path
     */
    public Path getArchiveDirectory() {
        return Paths.get(archiveDirectory);
    }

    /**
     * 서버 인스턴스 ID 생성
     * 호스트명을 우선 사용하고, 실패 시 UUID 사용
     */
    private static String resolveInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String uuid = UUID.randomUUID().toString();
            log.warn("⚠️ [Shutdown Persistence] 호스트명 조회 실패, UUID 사용: {}", uuid);
            return uuid;
        }
    }
}
