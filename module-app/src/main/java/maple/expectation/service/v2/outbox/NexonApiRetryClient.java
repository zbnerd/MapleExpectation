package maple.expectation.service.v2.outbox;

import maple.expectation.domain.v2.NexonApiOutbox;

/**
 * Nexon API 재시도 클라이언트 인터페이스 (N19)
 *
 * <p>Outbox에 적재된 Nexon API 호출을 재시도하기 위한 인터페이스.
 *
 * <h3>책임 분리</h3>
 *
 * <ul>
 *   <li>NexonApiClient: 실제 외부 API 호출 (캐싱, 회복탄력성 포함)
 *   <li>NexonApiRetryClient: Outbox 재시도 로직 (트랜잭션, DLQ 포함)
 * </ul>
 *
 * @see maple.expectation.external.NexonApiClient
 * @see NexonApiOutboxProcessor
 */
public interface NexonApiRetryClient {

  /**
   * Outbox 항목 처리
   *
   * @param outbox 처리할 Outbox 항목
   * @return 처리 성공 여부
   */
  boolean processOutboxEntry(NexonApiOutbox outbox);
}
