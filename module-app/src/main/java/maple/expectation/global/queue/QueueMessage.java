package maple.expectation.global.queue;

import java.time.Instant;

/**
 * 큐 메시지 래퍼 (msgId + payload + metadata)
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 *
 * <p>GPT-5 Iteration 4 (A) 반영: msgId 기반 ACK를 위한 메시지 래퍼
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Purple (Auditor): Record로 immutability 보장
 *   <li>Yellow (QA): retryCount로 재시도 추적
 * </ul>
 *
 * <h3>용도</h3>
 *
 * <ul>
 *   <li>consume() 반환 시 msgId와 payload를 함께 제공
 *   <li>ack(msgId) / nack(msgId, retryCount) 호출에 필요한 정보 제공
 *   <li>Re-drive 시 payload 복원에 사용
 * </ul>
 *
 * @param msgId 메시지 고유 식별자 (UUID)
 * @param payload 실제 메시지 페이로드
 * @param retryCount 재시도 횟수 (0부터 시작)
 * @param createdAt 메시지 생성 시각
 * @param <T> 페이로드 타입
 */
public record QueueMessage<T>(String msgId, T payload, int retryCount, Instant createdAt) {
  /**
   * 새 메시지 생성 (retryCount = 0)
   *
   * @param msgId 메시지 ID
   * @param payload 페이로드
   * @param <T> 페이로드 타입
   * @return 새 QueueMessage
   */
  public static <T> QueueMessage<T> of(String msgId, T payload) {
    return new QueueMessage<>(msgId, payload, 0, Instant.now());
  }

  /**
   * 재시도 메시지 생성 (retryCount 증가)
   *
   * @return retryCount가 1 증가한 새 QueueMessage
   */
  public QueueMessage<T> withIncrementedRetry() {
    return new QueueMessage<>(msgId, payload, retryCount + 1, createdAt);
  }

  /**
   * 재시도 횟수 직접 설정
   *
   * @param newRetryCount 새 재시도 횟수
   * @return 새 QueueMessage
   */
  public QueueMessage<T> withRetryCount(int newRetryCount) {
    return new QueueMessage<>(msgId, payload, newRetryCount, createdAt);
  }

  /**
   * 최대 재시도 횟수 초과 여부 확인
   *
   * @param maxRetries 최대 재시도 횟수
   * @return true: 최대 초과 (DLQ 이동 필요)
   */
  public boolean exceedsMaxRetries(int maxRetries) {
    return retryCount >= maxRetries;
  }
}
