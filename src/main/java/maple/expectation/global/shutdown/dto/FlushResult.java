package maple.expectation.global.shutdown.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * L1→L2 Flush 작업의 결과를 담는 DTO
 * <p>
 * {@code LikeSyncService.flushLocalToRedisWithFallback()} 메서드의
 * 반환값으로 사용되며, 성공/실패 건수를 추적합니다.
 * <p>
 * Java 17 record를 사용하여 immutable 데이터 구조를 보장합니다.
 *
 * @param redisSuccessCount Redis로 성공적으로 전송된 항목 수
 * @param fileBackupCount   Redis 실패로 로컬 파일에 백업된 항목 수
 */
public record FlushResult(
        int redisSuccessCount,
        int fileBackupCount
) {
    /**
     * 빈 결과 생성 (아무 작업도 수행하지 않음)
     */
    public static FlushResult empty() {
        return new FlushResult(0, 0);
    }

    /**
     * 성공 전용 결과 생성 (파일 백업 없음)
     */
    public static FlushResult success(int count) {
        return new FlushResult(count, 0);
    }

    /**
     * 실패 항목이 있는지 확인
     */
    @JsonIgnore
    public boolean hasFailures() {
        return fileBackupCount > 0;
    }

    /**
     * 모든 작업이 성공했는지 확인
     */
    @JsonIgnore
    public boolean isFullSuccess() {
        return fileBackupCount == 0 && redisSuccessCount > 0;
    }

    /**
     * 총 처리 항목 수
     */
    @JsonIgnore
    public int totalCount() {
        return redisSuccessCount + fileBackupCount;
    }

    /**
     * 성공률 계산 (0.0 ~ 1.0)
     */
    @JsonIgnore
    public double successRate() {
        int total = totalCount();
        return total == 0 ? 1.0 : (double) redisSuccessCount / total;
    }
}
