package maple.expectation.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Outbox 외부 설정 프로퍼티 (P1-2, P1-8)
 *
 * <h4>설계 의도</h4>
 *
 * <ul>
 *   <li>P1-8: BATCH_SIZE, STALE_THRESHOLD 하드코딩 제거 -> YAML 외부화
 *   <li>P1-2: instanceId @Value 주입 -> 생성자 주입으로 변경
 *   <li>P1-5: Exponential Backoff 최대 대기 시간 설정
 * </ul>
 *
 * <h4>CacheProperties 패턴 참조</h4>
 *
 * <p>{@code @ConfigurationProperties} + {@code @Validated}로 타입 안전 바인딩
 *
 * @see maple.expectation.service.v2.donation.outbox.OutboxProcessor
 */
@Validated
@ConfigurationProperties(prefix = "outbox")
public class OutboxProperties {

  /**
   * 배치 크기 (SKIP LOCKED 조회 단위)
   *
   * <p>기본값: 100건. 너무 크면 트랜잭션 락 시간 증가, 너무 작으면 오버헤드 증가
   */
  @Min(1)
  @Max(1000)
  private int batchSize = 100;

  /**
   * Stalled 판정 기준 시간
   *
   * <p>PROCESSING 상태로 이 시간 이상 유지된 항목을 Zombie로 판정
   */
  @NotNull private Duration staleThreshold = Duration.ofMinutes(5);

  /**
   * Exponential Backoff 최대 대기 시간 (P1-5 Fix)
   *
   * <p>retryCount가 커져도 이 시간을 초과하지 않음
   */
  @NotNull private Duration maxBackoff = Duration.ofHours(1);

  /** 인스턴스 식별자 (Scale-out 환경에서 어떤 인스턴스가 처리 중인지 식별) */
  @NotBlank private String instanceId = "default-instance";

  /**
   * Outbox 크기 경고 임계값 (Issue #N19)
   *
   * <p>이 값 초과 시 백로그 상태로 판단하고 로그 기록
   */
  @Min(100)
  @Max(100000)
  private int sizeAlertThreshold = 1000;

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public Duration getStaleThreshold() {
    return staleThreshold;
  }

  public void setStaleThreshold(Duration staleThreshold) {
    this.staleThreshold = staleThreshold;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }

  public void setMaxBackoff(Duration maxBackoff) {
    this.maxBackoff = maxBackoff;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setInstanceId(String instanceId) {
    this.instanceId = instanceId;
  }

  public int getSizeAlertThreshold() {
    return sizeAlertThreshold;
  }

  public void setSizeAlertThreshold(int sizeAlertThreshold) {
    this.sizeAlertThreshold = sizeAlertThreshold;
  }
}
