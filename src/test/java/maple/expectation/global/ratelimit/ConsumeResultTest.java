package maple.expectation.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ConsumeResult Record 테스트 (Issue #152) */
@DisplayName("ConsumeResult 테스트")
class ConsumeResultTest {

  @Nested
  @DisplayName("팩토리 메서드 테스트")
  class FactoryMethodTests {

    @Test
    @DisplayName("allowed() - 요청 허용 결과 생성")
    void allowed_CreatesAllowedResult() {
      // Given & When
      ConsumeResult result = ConsumeResult.allowed(50);

      // Then
      assertThat(result.allowed()).isTrue();
      assertThat(result.remainingTokens()).isEqualTo(50);
      assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("denied() - 요청 거부 결과 생성")
    void denied_CreatesDeniedResult() {
      // Given & When
      ConsumeResult result = ConsumeResult.denied(0, 30);

      // Then
      assertThat(result.allowed()).isFalse();
      assertThat(result.remainingTokens()).isZero();
      assertThat(result.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    @DisplayName("failOpen() - Redis 장애 시 Fail-Open 결과 생성")
    void failOpen_CreatesFailOpenResult() {
      // Given & When
      ConsumeResult result = ConsumeResult.failOpen();

      // Then
      assertThat(result.allowed()).isTrue();
      assertThat(result.remainingTokens()).isEqualTo(-1L); // 장애 표시
      assertThat(result.retryAfterSeconds()).isZero();
    }
  }

  @Nested
  @DisplayName("Record 동등성 테스트")
  class RecordEqualityTests {

    @Test
    @DisplayName("동일한 값을 가진 레코드는 동등함")
    void sameValues_AreEqual() {
      // Given
      ConsumeResult result1 = ConsumeResult.allowed(100);
      ConsumeResult result2 = ConsumeResult.allowed(100);

      // Then
      assertThat(result1).isEqualTo(result2);
      assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
    }

    @Test
    @DisplayName("다른 값을 가진 레코드는 동등하지 않음")
    void differentValues_AreNotEqual() {
      // Given
      ConsumeResult allowed = ConsumeResult.allowed(100);
      ConsumeResult denied = ConsumeResult.denied(0, 30);

      // Then
      assertThat(allowed).isNotEqualTo(denied);
    }
  }
}
