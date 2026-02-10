package maple.expectation.service.v2.shutdown;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.executor.LogicExecutor;
import maple.expectation.global.executor.TaskContext;
import maple.expectation.global.shutdown.dto.ShutdownData;
import maple.expectation.service.v2.LikeSyncExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShutdownDataRecoveryService {

  private final ShutdownDataPersistenceService persistenceService;
  private final LikeSyncExecutor syncExecutor;
  private final StringRedisTemplate redisTemplate;
  private final LogicExecutor executor; // ✅ 지능형 실행기 주입

  /**
   * Redis Hash Key (LikeSyncService.SOURCE_KEY와 동일)
   *
   * <p>CRITICAL FIX (PR #175, #164 Codex 지적): Hash Tag 패턴 적용으로 LikeSyncService와 키 일치 보장
   */
  private static final String REDIS_HASH_KEY = "{buffer:likes}";

  /**
   * 멱등성 보장용 Redis Set Key (Issue #127)
   *
   * <p>복구 프로세스가 중복 실행되어도 동일 항목이 재처리되지 않도록 처리 완료된 항목을 추적합니다.
   */
  private static final String PROCESSED_SET_KEY = "{recovery}:processed";

  private static final Duration PROCESSED_SET_TTL = Duration.ofHours(24);

  @PostConstruct
  public void recoverFromBackup() {
    TaskContext context = TaskContext.of("Recovery", "MainProcess");

    //  전체 복구 프로세스를 실행기로 보호 (Issue #77 대응)
    executor.executeVoid(
        () -> {
          log.info("🔄 [Shutdown Recovery] 백업 데이터 복구 시작");

          List<Path> backupFiles = persistenceService.findAllBackupFiles();
          if (backupFiles.isEmpty()) {
            log.info("✅ [Shutdown Recovery] 복구할 백업 파일 없음");
            return;
          }

          // 루프 내부의 try-catch를 메서드 참조와 실행기로 평탄화
          backupFiles.forEach(file -> processFileWithSafety(file, context));

          log.info("✅ [Shutdown Recovery] 백업 데이터 복구 완료");
        },
        context);
  }

  /** 개별 파일 처리 (관측성 격리) */
  private void processFileWithSafety(Path backupFile, TaskContext parentContext) {
    TaskContext fileContext =
        TaskContext.of("Recovery", "ProcessFile", backupFile.getFileName().toString());

    executor.executeVoid(
        () -> {
          boolean success = processBackupFile(backupFile);
          if (success) {
            persistenceService.archiveFile(backupFile); // [이슈 #123] 성공 시에만 아카이브
          } else {
            log.warn("⏭️ [Recovery Skip] 복구 미완료로 파일을 보존합니다: {}", backupFile.getFileName());
          }
        },
        fileContext);
  }

  /**
   * 백업 파일 처리 (P1 Fix: 부분 복구 중복 방지)
   *
   * <h4>변경 전 (버그)</h4>
   *
   * <ol>
   *   <li>Entry A 복구 성공
   *   <li>Entry B 복구 실패 → allSuccess=false
   *   <li>파일 미아카이브 → 재시작 시 A도 재복구 (중복!)
   * </ol>
   *
   * <h4>변경 후</h4>
   *
   * <ol>
   *   <li>성공/실패 항목 분리 추적
   *   <li>실패 항목만 새 백업 파일 저장
   *   <li>원본 파일은 항상 아카이브 (처리 완료 표시)
   * </ol>
   */
  private boolean processBackupFile(Path backupFile) {
    // P0-4 Fix: CLAUDE.md Section 4 - Optional Chaining Best Practice
    // .get() 대신 .map().orElse() 패턴 사용
    return persistenceService
        .readBackupFile(backupFile)
        .map(data -> processBackupData(backupFile, data))
        .orElse(false);
  }

  /** 백업 데이터 처리 (Optional 체이닝에서 분리) */
  private boolean processBackupData(Path backupFile, ShutdownData data) {
    log.info(
        "📝 [Shutdown Recovery] 처리 중: {} (항목: {}개)",
        backupFile.getFileName(),
        data.getTotalItems());

    // PR #187 Fix: 파일명 기반 결정론적 ID 전달
    String fileIdentifier = backupFile.getFileName().toString();
    Map<String, Long> failedEntries = recoverLikeBufferAndCollectFailures(data, fileIdentifier);
    recoverEquipmentPending(data);

    // 실패 항목이 있으면 새 백업 파일 생성 (성공 항목 제외)
    if (!failedEntries.isEmpty()) {
      log.warn(
          "⚠️ [Recovery] 부분 실패: {} 항목 중 {} 실패, 실패 항목만 백업 생성",
          data.likeBuffer().size(),
          failedEntries.size());
      persistenceService.saveFailedEntriesOnly(failedEntries, data.equipmentPending());
    }

    // 원본 파일은 항상 처리 완료로 간주 (재복구 방지)
    return true;
  }

  /**
   * PR #187: 멱등성 보장 - 파일명 기반 결정론적 ID
   *
   * <h4>변경 전 (버그)</h4>
   *
   * <p>{@code recoveryBatchId = UUID.randomUUID()} 사용으로 재시작 시 동일 항목 중복 처리
   *
   * <h4>변경 후</h4>
   *
   * <p>파일명 기반 결정론적 ID 사용 ({@code backupFileName:userIgn})으로 재시작 시에도 동일 항목은 스킵됨
   *
   * @param data 백업 데이터
   * @param fileIdentifier 백업 파일명 (결정론적 ID 생성용)
   * @return 복구 실패한 항목들
   */
  private Map<String, Long> recoverLikeBufferAndCollectFailures(
      ShutdownData data, String fileIdentifier) {
    Map<String, Long> likeBuffer = data.likeBuffer();
    if (likeBuffer == null || likeBuffer.isEmpty()) return Map.of();

    // P1-9 Fix: forEach는 순차 실행, executeOrCatch도 동기 → HashMap 충분
    Map<String, Long> failedEntries = new java.util.HashMap<>();

    likeBuffer.forEach(
        (userIgn, count) -> {
          // PR #187 Fix: 파일명 기반 결정론적 ID (UUID.randomUUID() 제거)
          String entryId = fileIdentifier + ":" + userIgn;
          TaskContext entryContext = TaskContext.of("Recovery", "LikeEntry", userIgn);

          executor.executeOrCatch(
              () -> {
                // Issue #127: 멱등성 체크 - 이미 처리된 항목 스킵
                if (Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(PROCESSED_SET_KEY, entryId))) {
                  log.debug("⏭️ [Shutdown Recovery] 이미 처리됨, 스킵: {}", userIgn);
                  return null;
                }

                // Redis Hash에 좋아요 수 복구
                redisTemplate.opsForHash().increment(REDIS_HASH_KEY, userIgn, count);

                // 처리 완료 마킹 (멱등성 보장)
                redisTemplate.opsForSet().add(PROCESSED_SET_KEY, entryId);
                redisTemplate.expire(PROCESSED_SET_KEY, PROCESSED_SET_TTL);

                log.debug("✅ [Shutdown Recovery] Redis 복구 성공: {} ({}건)", userIgn, count);
                return null;
              },
              (redisEx) -> recoverToDbOrCollectFailure(userIgn, count, failedEntries, entryContext),
              entryContext);
        });

    return failedEntries;
  }

  /** DB Fallback 시도, 최종 실패 시 failedEntries에 수집 */
  private Void recoverToDbOrCollectFailure(
      String userIgn, Long count, Map<String, Long> failedEntries, TaskContext context) {
    log.warn("⚠️ [Shutdown Recovery] Redis 복구 실패, DB 직접 반영 시도: {} ({}건)", userIgn, count);

    return executor.executeOrCatch(
        () -> {
          syncExecutor.executeIncrement(userIgn, count);
          log.info("✅ [Shutdown Recovery] DB 직접 반영 성공: {} ({}건)", userIgn, count);
          return null;
        },
        (dbEx) -> {
          log.error("❌ [Shutdown Recovery] 최종 복구 실패 - 재시도 예정: {} ({}건)", userIgn, count);
          failedEntries.put(userIgn, count); // P1 Fix: 실패 항목만 수집
          return null;
        },
        context);
  }

  private void recoverEquipmentPending(ShutdownData data) {
    List<String> equipmentPending = data.equipmentPending();
    if (equipmentPending == null || equipmentPending.isEmpty()) return;

    log.warn("⚠️ [Shutdown Recovery] Equipment 미완료 항목: {}건", equipmentPending.size());
    log.warn("   → OCID 목록: {}", equipmentPending);
  }
}
