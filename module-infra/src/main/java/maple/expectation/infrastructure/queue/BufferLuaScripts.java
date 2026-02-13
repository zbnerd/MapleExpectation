package maple.expectation.infrastructure.queue;

/**
 * Redis Buffer Lua Script 상수 클래스
 *
 * <h3>V5 Stateless Architecture (#271)</h3>
 *
 * <p>Redis 싱글 스레드 특성을 활용하여 원자적 연산을 보장합니다. 모든 스크립트는 Hash Tag를 사용하여 Redis Cluster CROSSSLOT 에러를
 * 방지합니다.
 *
 * <h3>GPT-5 Iteration 4 반영 (CRITICAL)</h3>
 *
 * <ul>
 *   <li>(A) ACK는 msgId 기반 - payload 직렬화 불일치 방지
 *   <li>(B) Payload Store 분리 - Re-drive 시 복원 가능
 *   <li>(C) Retry ZSET 메커니즘 - Delayed Retry 지원
 *   <li>(1) ACK/Redrive 레이스 Lua 원자화
 * </ul>
 *
 * <h3>Reliable Queue 패턴</h3>
 *
 * <pre>
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │            Reliable Queue 패턴 (At-Least-Once + 만료 판정)              │
 * ├───────────────────────────────────────────────────────────────────────┤
 * │                                                                        │
 * │  Main Queue: {expectation}:buffer (List of msgId)                     │
 * │  Inflight:   {expectation}:buffer:inflight (List of msgId)            │
 * │  Inflight TS: {expectation}:buffer:inflight:ts (ZSET: score=timestamp)│
 * │  Payload:    {expectation}:buffer:payload (HASH: msgId → JSON)        │
 * │  Retry:      {expectation}:buffer:retry (ZSET: score=nextAttemptAt)   │
 * │  DLQ:        {expectation}:buffer:dlq (List)                          │
 * │                                                                        │
 * └───────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Reliable Queue 패턴으로 At-Least-Once 보장
 *   <li>Green (Performance): Lua Script로 RTT 최소화 (6 commands → 1 RTT)
 *   <li>Purple (Auditor): 멱등성 보장으로 중복 처리 방지
 *   <li>Yellow (QA): ACK/Redrive 레이스 테스트 가능한 구조
 *   <li>Red (SRE): DLQ로 실패 메시지 격리
 * </ul>
 */
public final class BufferLuaScripts {

  private BufferLuaScripts() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Script 1: Publish (메시지 발행)
   *
   * <p>msgId를 Main Queue에 추가하고 Payload를 HASH에 저장합니다.
   *
   * <pre>
   * KEYS[1] = mainQueue ({expectation}:buffer)
   * KEYS[2] = payload ({expectation}:buffer:payload)
   * ARGV[1] = msgId
   * ARGV[2] = payloadJson
   *
   * Returns: 1 (항상 성공)
   * </pre>
   *
   * <h4>원자성</h4>
   *
   * <p>HSET + RPUSH가 단일 트랜잭션으로 실행되어 중간 크래시 시 데이터 불일치 방지
   */
  public static final String PUBLISH =
      """
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
            redis.call('RPUSH', KEYS[1], ARGV[1])
            return 1
            """;

  /**
   * Script 2: Consume (배치 소비)
   *
   * <p>Main Queue에서 INFLIGHT로 원자적 이동 + 타임스탬프 기록 + Payload 조회
   *
   * <pre>
   * KEYS[1] = mainQueue ({expectation}:buffer)
   * KEYS[2] = inflight ({expectation}:buffer:inflight)
   * KEYS[3] = inflightTs ({expectation}:buffer:inflight:ts)
   * KEYS[4] = payload ({expectation}:buffer:payload)
   * ARGV[1] = batchSize (최대 소비 개수)
   * ARGV[2] = timestamp (현재 시각 ms)
   *
   * Returns: [[msgId1, payloadJson1], [msgId2, payloadJson2], ...]
   * </pre>
   *
   * <h4>원자성</h4>
   *
   * <p>RPOPLPUSH + ZADD + HGET가 단일 트랜잭션으로 실행되어 소비 중 크래시 시 메시지 유실 방지 (INFLIGHT에 있으므로 Re-drive 가능)
   */
  public static final String CONSUME =
      """
            local result = {}
            for i = 1, tonumber(ARGV[1]) do
                local msgId = redis.call('RPOPLPUSH', KEYS[1], KEYS[2])
                if not msgId then
                    break
                end
                redis.call('ZADD', KEYS[3], ARGV[2], msgId)
                local payload = redis.call('HGET', KEYS[4], msgId)
                table.insert(result, {msgId, payload})
            end
            return result
            """;

  /**
   * Script 3: ACK (처리 완료)
   *
   * <p>INFLIGHT에서 메시지 제거 + 타임스탬프 ZSET 제거 + Payload 삭제
   *
   * <pre>
   * KEYS[1] = inflight ({expectation}:buffer:inflight)
   * KEYS[2] = inflightTs ({expectation}:buffer:inflight:ts)
   * KEYS[3] = payload ({expectation}:buffer:payload)
   * ARGV[1] = msgId
   *
   * Returns:
   *   1 = 정상 ACK
   *   0 = 이미 ACK됨 (멱등성)
   * </pre>
   *
   * <h4>GPT-5 Iteration 4 (A): msgId 기반 ACK</h4>
   *
   * <p>payload 직렬화 불일치로 인한 LREM 실패 방지
   *
   * <h4>멱등성</h4>
   *
   * <p>LREM이 0을 반환하면 이미 ACK된 것이므로 ZREM/HDEL 스킵
   */
  public static final String ACK =
      """
            local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
            if removed > 0 then
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('HDEL', KEYS[3], ARGV[1])
            end
            return removed
            """;

  /**
   * Script 4: NACK (처리 실패 - Retry Queue 등록)
   *
   * <p>INFLIGHT에서 제거 후 Retry Queue에 등록 (Delayed Retry)
   *
   * <pre>
   * KEYS[1] = inflight ({expectation}:buffer:inflight)
   * KEYS[2] = inflightTs ({expectation}:buffer:inflight:ts)
   * KEYS[3] = retry ({expectation}:buffer:retry)
   * KEYS[4] = payload ({expectation}:buffer:payload)
   * ARGV[1] = msgId
   * ARGV[2] = nextAttemptAtMs (다음 재시도 시각)
   * ARGV[3] = retryCount (현재 재시도 횟수)
   * ARGV[4] = updatedPayloadJson (retryCount 증가된 payload)
   *
   * Returns: 1 (항상 성공)
   * </pre>
   *
   * <h4>GPT-5 Iteration 4 (C): Retry ZSET 메커니즘</h4>
   *
   * <p>score = nextAttemptAtMs로 지연 재시도 구현
   */
  public static final String NACK_TO_RETRY =
      """
            redis.call('LREM', KEYS[1], 1, ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])
            redis.call('HSET', KEYS[4], ARGV[1], ARGV[4])
            return 1
            """;

  /**
   * Script 5: NACK (처리 실패 - DLQ 이동)
   *
   * <p>최대 재시도 초과 시 DLQ로 이동
   *
   * <pre>
   * KEYS[1] = inflight ({expectation}:buffer:inflight)
   * KEYS[2] = inflightTs ({expectation}:buffer:inflight:ts)
   * KEYS[3] = dlq ({expectation}:buffer:dlq)
   * ARGV[1] = msgId
   *
   * Returns: 1 (항상 성공)
   * </pre>
   *
   * <h4>DLQ 정책</h4>
   *
   * <p>Payload는 유지하여 수동 재처리 가능
   */
  public static final String NACK_TO_DLQ =
      """
            redis.call('LREM', KEYS[1], 1, ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            redis.call('RPUSH', KEYS[3], ARGV[1])
            return 1
            """;

  /**
   * Script 6: Redrive (만료된 INFLIGHT → Main Queue 복귀)
   *
   * <p>타임아웃된 메시지를 Main Queue로 복귀시킵니다.
   *
   * <pre>
   * KEYS[1] = inflight ({expectation}:buffer:inflight)
   * KEYS[2] = inflightTs ({expectation}:buffer:inflight:ts)
   * KEYS[3] = mainQueue ({expectation}:buffer)
   * ARGV[1] = msgId
   *
   * Returns:
   *   1 = 복귀 성공
   *   0 = 이미 ACK됨 (멱등성)
   * </pre>
   *
   * <h4>GPT-5 Iteration 4 (1): ACK/Redrive 레이스 방지</h4>
   *
   * <p>LPOS로 존재 확인 후 이동하여 ACK와 충돌 방지
   */
  public static final String REDRIVE =
      """
            local exists = redis.call('LPOS', KEYS[1], ARGV[1])
            if exists then
                redis.call('LREM', KEYS[1], 1, ARGV[1])
                redis.call('ZREM', KEYS[2], ARGV[1])
                redis.call('RPUSH', KEYS[3], ARGV[1])
                return 1
            end
            return 0
            """;

  /**
   * Script 7: Process Retry Queue (Retry → Main Queue)
   *
   * <p>만료된 Retry 메시지를 Main Queue로 복귀시킵니다.
   *
   * <pre>
   * KEYS[1] = retry ({expectation}:buffer:retry)
   * KEYS[2] = mainQueue ({expectation}:buffer)
   * ARGV[1] = maxScore (현재 시각 ms)
   * ARGV[2] = limit (최대 처리 개수)
   *
   * Returns: 처리된 msgId 목록
   * </pre>
   */
  public static final String PROCESS_RETRY_QUEUE =
      """
            local ready = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
            for _, msgId in ipairs(ready) do
                redis.call('ZREM', KEYS[1], msgId)
                redis.call('RPUSH', KEYS[2], msgId)
            end
            return ready
            """;

  /**
   * Script 8: Get Expired Inflight Messages
   *
   * <p>만료된 INFLIGHT 메시지 ID 목록을 조회합니다.
   *
   * <pre>
   * KEYS[1] = inflightTs ({expectation}:buffer:inflight:ts)
   * ARGV[1] = maxScore (타임아웃 기준 시각 ms)
   * ARGV[2] = limit (최대 조회 개수)
   *
   * Returns: 만료된 msgId 목록
   * </pre>
   */
  public static final String GET_EXPIRED_INFLIGHT =
      """
            return redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
            """;

  /**
   * Script 9: Get Queue Counts (모니터링용)
   *
   * <pre>
   * KEYS[1] = mainQueue ({expectation}:buffer)
   * KEYS[2] = inflight ({expectation}:buffer:inflight)
   * KEYS[3] = retry ({expectation}:buffer:retry)
   * KEYS[4] = dlq ({expectation}:buffer:dlq)
   *
   * Returns: [pendingCount, inflightCount, retryCount, dlqCount]
   * </pre>
   */
  public static final String GET_QUEUE_COUNTS =
      """
            local pending = redis.call('LLEN', KEYS[1])
            local inflight = redis.call('LLEN', KEYS[2])
            local retry = redis.call('ZCARD', KEYS[3])
            local dlq = redis.call('LLEN', KEYS[4])
            return {pending, inflight, retry, dlq}
            """;
}
