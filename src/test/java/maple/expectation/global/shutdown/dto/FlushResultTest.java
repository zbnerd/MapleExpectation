package maple.expectation.global.shutdown.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlushResult DTO 테스트
 */
@DisplayName("FlushResult DTO 테스트")
class FlushResultTest {

    @Test
    @DisplayName("빈 FlushResult 생성 테스트")
    void testEmptyFlushResult() {
        // when
        FlushResult result = FlushResult.empty();

        // then
        assertThat(result).isNotNull();
        assertThat(result.redisSuccessCount()).isZero();
        assertThat(result.fileBackupCount()).isZero();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.isFullSuccess()).isFalse(); // 성공 건수도 0이므로 false
        assertThat(result.totalCount()).isZero();
        assertThat(result.successRate()).isEqualTo(1.0); // 0/0 = 1.0 (기본값)
    }

    @Test
    @DisplayName("성공 전용 FlushResult 생성 테스트")
    void testSuccessOnlyFlushResult() {
        // when
        FlushResult result = FlushResult.success(10);

        // then
        assertThat(result.redisSuccessCount()).isEqualTo(10);
        assertThat(result.fileBackupCount()).isZero();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.isFullSuccess()).isTrue();
        assertThat(result.totalCount()).isEqualTo(10);
        assertThat(result.successRate()).isEqualTo(1.0); // 10/10 = 1.0
    }

    @Test
    @DisplayName("실패 항목이 있는 FlushResult 테스트")
    void testFlushResultWithFailures() {
        // given
        FlushResult result = new FlushResult(7, 3);

        // then
        assertThat(result.redisSuccessCount()).isEqualTo(7);
        assertThat(result.fileBackupCount()).isEqualTo(3);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.isFullSuccess()).isFalse();
        assertThat(result.totalCount()).isEqualTo(10);
        assertThat(result.successRate()).isEqualTo(0.7); // 7/10 = 0.7
    }

    @Test
    @DisplayName("모든 항목이 실패한 FlushResult 테스트")
    void testAllFailuresFlushResult() {
        // given
        FlushResult result = new FlushResult(0, 10);

        // then
        assertThat(result.redisSuccessCount()).isZero();
        assertThat(result.fileBackupCount()).isEqualTo(10);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.isFullSuccess()).isFalse();
        assertThat(result.totalCount()).isEqualTo(10);
        assertThat(result.successRate()).isZero(); // 0/10 = 0.0
    }

    @Test
    @DisplayName("successRate 계산 정확도 테스트")
    void testSuccessRateAccuracy() {
        // given
        FlushResult result1 = new FlushResult(1, 2); // 1/3 = 0.333...
        FlushResult result2 = new FlushResult(2, 1); // 2/3 = 0.666...
        FlushResult result3 = new FlushResult(50, 50); // 50/100 = 0.5

        // then
        assertThat(result1.successRate()).isCloseTo(0.333, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result2.successRate()).isCloseTo(0.666, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result3.successRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("record equality 테스트")
    void testRecordEquality() {
        // given
        FlushResult result1 = new FlushResult(10, 5);
        FlushResult result2 = new FlushResult(10, 5);
        FlushResult result3 = new FlushResult(10, 6);

        // then
        assertThat(result1).isEqualTo(result2);
        assertThat(result1).isNotEqualTo(result3);
        assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
    }

    @Test
    @DisplayName("totalCount 계산 테스트")
    void testTotalCount() {
        // given
        FlushResult result1 = new FlushResult(100, 0);
        FlushResult result2 = new FlushResult(0, 100);
        FlushResult result3 = new FlushResult(50, 50);

        // then
        assertThat(result1.totalCount()).isEqualTo(100);
        assertThat(result2.totalCount()).isEqualTo(100);
        assertThat(result3.totalCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("isFullSuccess 다양한 케이스 테스트")
    void testIsFullSuccess() {
        // given
        FlushResult allSuccess = new FlushResult(10, 0);
        FlushResult partialSuccess = new FlushResult(5, 5);
        FlushResult allFailed = new FlushResult(0, 10);
        FlushResult noData = new FlushResult(0, 0);

        // then
        assertThat(allSuccess.isFullSuccess()).isTrue();
        assertThat(partialSuccess.isFullSuccess()).isFalse();
        assertThat(allFailed.isFullSuccess()).isFalse();
        assertThat(noData.isFullSuccess()).isFalse();
    }
}
