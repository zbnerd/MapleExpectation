package maple.expectation.service.v2.like.event;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import maple.expectation.service.v2.like.dto.FetchResult;

/**
 * LikeSync 복구 실패 이벤트 (DLQ 패턴)
 *
 * <p>금융수준 안전 설계:
 *
 * <ul>
 *   <li>보상 트랜잭션 실패 시 데이터 영구 손실 방지
 *   <li>Event 발행 → Listener에서 파일 백업
 *   <li>수동 복구 가능한 형태로 데이터 보존
 * </ul>
 *
 * @param tempKey 실패한 임시 키 (nullable - 단일 엔트리 실패 시)
 * @param sourceKey 복원 대상 원본 키
 * @param data 손실 위험 데이터 (userIgn -> count)
 * @param failedAt 실패 시각
 * @param errorMessage 실패 원인
 * @since 2.0.0
 */
public record LikeSyncFailedEvent(
    String tempKey,
    String sourceKey,
    Map<String, Long> data,
    Instant failedAt,
    String errorMessage) {

  /** Compact Constructor: 불변성 보장 */
  public LikeSyncFailedEvent {
    data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
  }

  /** FetchResult로부터 실패 이벤트 생성 (전체 보상 실패) */
  public static LikeSyncFailedEvent fromFetchResult(
      FetchResult result, String sourceKey, Throwable cause) {
    return new LikeSyncFailedEvent(
        result.tempKey(),
        sourceKey,
        result.data(),
        Instant.now(),
        cause != null ? cause.getMessage() : "Unknown error");
  }

  /** 단일 엔트리 실패 이벤트 생성 (개별 복구 실패) */
  public static LikeSyncFailedEvent forSingleEntry(
      String userIgn, long count, String sourceKey, Throwable cause) {
    return new LikeSyncFailedEvent(
        null,
        sourceKey,
        Map.of(userIgn, count),
        Instant.now(),
        cause != null ? cause.getMessage() : "Unknown error");
  }

  /** 총 손실 위험 건수 */
  public long totalCount() {
    return data.values().stream().mapToLong(Long::longValue).sum();
  }

  /** 엔트리 수 */
  public int size() {
    return data.size();
  }

  /** 하위 호환성 - 단일 엔트리 실패 시 */
  public String userIgn() {
    return data.isEmpty() ? null : data.keySet().iterator().next();
  }

  /** 하위 호환성 */
  public long lostCount() {
    return totalCount();
  }

  /** 하위 호환성 */
  public Exception exception() {
    // NOTE: Returns RuntimeException for backward compatibility with legacy code.
    // This is a deprecated method - prefer using errorMessage() directly.
    return new RuntimeException(errorMessage);
  }
}
