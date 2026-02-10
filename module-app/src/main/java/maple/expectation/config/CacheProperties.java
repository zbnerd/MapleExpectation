package maple.expectation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * TieredCache 외부 설정 프로퍼티 (P1-2, P1-5, P0-4)
 *
 * <h4>설계 의도</h4>
 *
 * <ul>
 *   <li>P1-2: CacheConfig TTL/Size 하드코딩 제거 → YAML 외부화
 *   <li>P1-5: Lock timeout 하드코딩 제거 → YAML 외부화
 *   <li>P0-4: lockWaitSeconds 30초 → 5초 (cold cache burst 스레드 고갈 방지)
 * </ul>
 *
 * <h4>NexonApiProperties 패턴 참조</h4>
 *
 * <p>{@code @ConfigurationProperties} + {@code @Validated}로 타입 안전 바인딩
 *
 * @see CacheConfig
 */
@Validated
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

  /**
   * 캐시별 L1/L2 스펙 설정
   *
   * <p>key: 캐시 이름 (equipment, cubeTrials, ocidCache, characterBasic, expectationV4)
   */
  @NotNull @Valid private Map<String, CacheSpec> specs = Map.of();

  /** Singleflight (분산 락) 설정 */
  @NotNull @Valid private Singleflight singleflight = new Singleflight();

  public Map<String, CacheSpec> getSpecs() {
    return specs;
  }

  public void setSpecs(Map<String, CacheSpec> specs) {
    this.specs = specs;
  }

  public Singleflight getSingleflight() {
    return singleflight;
  }

  public void setSingleflight(Singleflight singleflight) {
    this.singleflight = singleflight;
  }

  /**
   * 캐시별 L1/L2 스펙
   *
   * <ul>
   *   <li>l1TtlMinutes: L1(Caffeine) TTL (분)
   *   <li>l1MaxSize: L1 최대 엔트리 수
   *   <li>l2TtlMinutes: L2(Redis) TTL (분)
   *   <li>l2Serializer: L2 직렬화 방식 (json | jdk)
   * </ul>
   */
  public static class CacheSpec {

    @Min(1)
    @Max(1440)
    private int l1TtlMinutes = 10;

    @Min(100)
    @Max(100000)
    private int l1MaxSize = 5000;

    @Min(1)
    @Max(1440)
    private int l2TtlMinutes = 15;

    @NotNull private String l2Serializer = "json";

    public int getL1TtlMinutes() {
      return l1TtlMinutes;
    }

    public void setL1TtlMinutes(int l1TtlMinutes) {
      this.l1TtlMinutes = l1TtlMinutes;
    }

    public int getL1MaxSize() {
      return l1MaxSize;
    }

    public void setL1MaxSize(int l1MaxSize) {
      this.l1MaxSize = l1MaxSize;
    }

    public int getL2TtlMinutes() {
      return l2TtlMinutes;
    }

    public void setL2TtlMinutes(int l2TtlMinutes) {
      this.l2TtlMinutes = l2TtlMinutes;
    }

    public String getL2Serializer() {
      return l2Serializer;
    }

    public void setL2Serializer(String l2Serializer) {
      this.l2Serializer = l2Serializer;
    }
  }

  /**
   * Singleflight (분산 락) 설정
   *
   * <h4>P0-4 Fix: lockWaitSeconds 기본값 5초</h4>
   *
   * <p>캐시 valueLoader는 보통 100-500ms. 5초면 충분하고, 5초 초과 시 fallback 직접 실행이 합리적.
   *
   * <p>기존 30초 → 5초로 변경하여 cold cache burst 시 스레드 고갈 방지
   */
  public static class Singleflight {

    @Min(1)
    @Max(60)
    private int lockWaitSeconds = 5;

    public int getLockWaitSeconds() {
      return lockWaitSeconds;
    }

    public void setLockWaitSeconds(int lockWaitSeconds) {
      this.lockWaitSeconds = lockWaitSeconds;
    }
  }
}
