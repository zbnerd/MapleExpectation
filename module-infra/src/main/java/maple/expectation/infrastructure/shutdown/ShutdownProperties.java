package maple.expectation.infrastructure.shutdown;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Graceful Shutdown 외부 설정 프로퍼티 (P1-1, P1-2, P1-3, P1-5)
 *
 * <h4>설계 의도</h4>
 *
 * <ul>
 *   <li>P1-1: backupDirectory, archiveDirectory @Value 주입 -> 생성자 주입 변경
 *   <li>P1-2: Equipment 대기 타임아웃, 분산 락 설정 하드코딩 제거
 *   <li>P1-3: Shutdown batch size, retry count 외부화
 *   <li>P1-5: instanceId 외부 설정으로 DNS 블로킹 제거
 * </ul>
 *
 * <h4>OutboxProperties 패턴 참조</h4>
 *
 * <p>{@code @ConfigurationProperties} + {@code @Validated}로 타입 안전 바인딩
 *
 * @see maple.expectation.infrastructure.shutdown.GracefulShutdownCoordinator
 * @see maple.expectation.service.v4.buffer.ExpectationBatchShutdownHandler
 * @see maple.expectation.service.v2.shutdown.ShutdownDataPersistenceService
 */
@Validated
@ConfigurationProperties(prefix = "shutdown")
public class ShutdownProperties {

  // ========== Coordinator 설정 (P1-2) ==========

  /**
   * Equipment 비동기 저장 대기 타임아웃
   *
   * <p>기본값: 20초. Shutdown 시 Equipment 저장 완료 대기 최대 시간
   */
  @NotNull private Duration equipmentAwaitTimeout = Duration.ofSeconds(20);

  /**
   * DB 동기화 분산 락 대기 시간 (초)
   *
   * <p>Scale-out 시 다른 인스턴스와 락 경합 대기 시간
   */
  @Min(1)
  @Max(30)
  private int lockWaitSeconds = 3;

  /** DB 동기화 분산 락 점유 시간 (초) */
  @Min(5)
  @Max(60)
  private int lockLeaseSeconds = 10;

  // ========== Batch Shutdown Handler 설정 (P1-3) ==========

  /**
   * Shutdown 시 배치 크기
   *
   * <p>빠른 종료를 위해 일반 배치보다 큰 크기 사용
   */
  @Min(50)
  @Max(1000)
  private int batchSize = 200;

  /**
   * 빈 배치 재시도 횟수
   *
   * <p>Race condition으로 인한 일시적 빈 배치 대응
   */
  @Min(1)
  @Max(10)
  private int emptyBatchRetryCount = 3;

  /** 빈 배치 간 대기 시간 (밀리초) */
  @Min(50)
  @Max(1000)
  private long emptyBatchWaitMs = 100;

  // ========== Persistence 설정 (P1-1, P1-5) ==========

  /** 백업 파일 저장 디렉토리 */
  @NotBlank private String backupDirectory = "/tmp/maple-shutdown";

  /** 아카이브 디렉토리 (처리 완료 파일 이동) */
  @NotBlank private String archiveDirectory = "/tmp/maple-shutdown/processed";

  /**
   * 인스턴스 식별자 (P1-5 Fix: DNS 블로킹 제거)
   *
   * <p>Scale-out 환경에서 어떤 인스턴스의 백업인지 식별
   */
  @NotBlank private String instanceId = "default-instance";

  // ========== Getters & Setters ==========

  public Duration getEquipmentAwaitTimeout() {
    return equipmentAwaitTimeout;
  }

  public void setEquipmentAwaitTimeout(Duration equipmentAwaitTimeout) {
    this.equipmentAwaitTimeout = equipmentAwaitTimeout;
  }

  public int getLockWaitSeconds() {
    return lockWaitSeconds;
  }

  public void setLockWaitSeconds(int lockWaitSeconds) {
    this.lockWaitSeconds = lockWaitSeconds;
  }

  public int getLockLeaseSeconds() {
    return lockLeaseSeconds;
  }

  public void setLockLeaseSeconds(int lockLeaseSeconds) {
    this.lockLeaseSeconds = lockLeaseSeconds;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getEmptyBatchRetryCount() {
    return emptyBatchRetryCount;
  }

  public void setEmptyBatchRetryCount(int emptyBatchRetryCount) {
    this.emptyBatchRetryCount = emptyBatchRetryCount;
  }

  public long getEmptyBatchWaitMs() {
    return emptyBatchWaitMs;
  }

  public void setEmptyBatchWaitMs(long emptyBatchWaitMs) {
    this.emptyBatchWaitMs = emptyBatchWaitMs;
  }

  public String getBackupDirectory() {
    return backupDirectory;
  }

  public void setBackupDirectory(String backupDirectory) {
    this.backupDirectory = backupDirectory;
  }

  public String getArchiveDirectory() {
    return archiveDirectory;
  }

  public void setArchiveDirectory(String archiveDirectory) {
    this.archiveDirectory = archiveDirectory;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }
}
