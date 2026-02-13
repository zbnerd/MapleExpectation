package maple.expectation.infrastructure.shutdown.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Shutdown 시 백업되는 데이터 구조
 *
 * <p>Redis 장애나 DB 장애 시 로컬 파일로 백업되며, 재시작 시 이 데이터를 복구하여 데이터 유실을 방지합니다.
 *
 * <p>Java 17 record를 사용하여 immutable 데이터 구조를 보장합니다. Jackson은 record를 자동으로 직렬화/역직렬화합니다 (Spring Boot
 * 3.x).
 *
 * @param timestamp 백업 생성 시각 (ISO 8601 형식으로 직렬화됨)
 * @param instanceId 서버 인스턴스 ID (호스트명 또는 UUID)
 * @param likeBuffer 미처리된 좋아요 버퍼 (userIgn -> count)
 * @param equipmentPending 미완료 Equipment 비동기 저장 OCID 목록
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShutdownData(
    LocalDateTime timestamp,
    String instanceId,
    Map<String, Long> likeBuffer,
    List<String> equipmentPending) {
  /** 빈 백업 데이터 생성 */
  public static ShutdownData empty(String instanceId) {
    return new ShutdownData(LocalDateTime.now(), instanceId, Map.of(), List.of());
  }

  /** 백업 데이터가 비어있는지 확인 */
  @JsonIgnore
  public boolean isEmpty() {
    return (likeBuffer == null || likeBuffer.isEmpty())
        && (equipmentPending == null || equipmentPending.isEmpty());
  }

  /** 총 항목 수 반환 (모니터링용) */
  @JsonIgnore
  public int getTotalItems() {
    int likeCount = likeBuffer != null ? likeBuffer.size() : 0;
    int equipmentCount = equipmentPending != null ? equipmentPending.size() : 0;
    return likeCount + equipmentCount;
  }
}
