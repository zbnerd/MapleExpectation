package maple.expectation.infrastructure.resilience;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MySQL Resilience 설정 프로퍼티 (Issue #218)
 *
 * <p>application.yml의 resilience.mysql-fallback 설정을 바인딩합니다.
 *
 * <p>P1 Externalization: syncBatchSize는 expectation.batch.mysql-fallback-sync-size에서 주입받습니다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resilience.mysql-fallback")
public class MySQLFallbackProperties {

  /** MySQL Fallback 기능 활성화 여부 */
  private boolean enabled = true;

  /** MySQL 상태 저장 키 (Redis Hash Tag 적용) */
  private String stateKey = "{mysql}:state";

  /** TTL 관리용 분산 락 키 */
  private String ttlLockKey = "{mysql}:ttl:lock";

  /** Compensation Log Stream 키 */
  private String compensationStream = "{mysql}:compensation:stream";

  /** Compensation DLQ 키 */
  private String compensationDlq = "{mysql}:compensation:dlq";

  /** Debounce 대기 시간 (초) - Flapping 방지 */
  private int debounceSeconds = 5;

  /** 상태 키 TTL (초) - 인스턴스 크래시 대비 */
  private int stateTtlSeconds = 300;

  /** Sync 배치 크기 - BatchProperties에서 주입됨 */
  private int syncBatchSize = 100; // 기본값 유지 (역호환성)

  /** Sync 최대 재시도 횟수 */
  private int syncMaxRetries = 3;

  /** Consumer Group 이름 */
  private String syncConsumerGroup = "compensation-sync";

  /** 대상 캐시 패턴 목록 */
  private List<String> targetCachePatterns = List.of("equipment:*", "ocidCache:*");

  /** SCAN COUNT 설정 */
  private int scanCount = 1000;

  /** Stream MAXLEN 설정 */
  private int streamMaxLen = 10000;

  /** 분산 락 대기 시간 (초) */
  private int lockWaitSeconds = 5;

  /** 분산 락 임대 시간 (초) */
  private int lockLeaseSeconds = 30;
}
