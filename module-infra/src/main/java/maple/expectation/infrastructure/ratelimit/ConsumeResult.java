package maple.expectation.infrastructure.ratelimit;

/**
 * Rate Limit 토큰 소비 결과 (Immutable Record)
 *
 * <p>CLAUDE.md 섹션 4 준수: Java 17 Record로 불변성 보장
 *
 * <h4>사용 예시</h4>
 *
 * <pre>{@code
 * ConsumeResult result = rateLimiter.tryConsume("192.168.1.1");
 * if (!result.allowed()) {
 *     throw new RateLimitExceededException(result.retryAfterSeconds());
 * }
 * }</pre>
 *
 * @param allowed 요청 허용 여부
 * @param remainingTokens 남은 토큰 수 (메트릭 및 헤더용)
 * @param retryAfterSeconds 429 응답 시 Retry-After 헤더 값 (초)
 * @since Issue #152
 */
public record ConsumeResult(boolean allowed, long remainingTokens, long retryAfterSeconds) {
  /**
   * 요청 허용 결과 생성
   *
   * @param remainingTokens 남은 토큰 수
   * @return 허용 결과
   */
  public static ConsumeResult allowed(long remainingTokens) {
    return new ConsumeResult(true, remainingTokens, 0L);
  }

  /**
   * 요청 거부 결과 생성
   *
   * @param remainingTokens 남은 토큰 수 (0 예상)
   * @param retryAfterSeconds 재시도까지 대기 시간 (초)
   * @return 거부 결과
   */
  public static ConsumeResult denied(long remainingTokens, long retryAfterSeconds) {
    return new ConsumeResult(false, remainingTokens, retryAfterSeconds);
  }

  /**
   * Redis 장애 시 Fail-Open 허용 결과 생성
   *
   * <p>5-Agent Council 합의: 가용성 > 보안
   *
   * @return 허용 결과 (남은 토큰 -1로 장애 상황 표시)
   */
  public static ConsumeResult failOpen() {
    return new ConsumeResult(true, -1L, 0L);
  }
}
