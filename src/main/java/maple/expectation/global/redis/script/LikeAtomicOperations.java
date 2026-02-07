package maple.expectation.global.redis.script;

/**
 * 좋아요 관련 원자적 Redis 연산 인터페이스 (Strategy Pattern)
 *
 * <p>ISP(Interface Segregation Principle)를 준수하여 좋아요 동기화에 필요한 원자적 연산만 정의합니다.
 *
 * <h2>구현체</h2>
 *
 * <ul>
 *   <li>{@link RedissonLikeAtomicOperations} - Redisson 기반 Lua Script 구현
 * </ul>
 *
 * <h2>원자성 보장</h2>
 *
 * <p>모든 메서드는 Redis Lua Script로 구현되어 원자성이 보장됩니다. 중간 크래시 시에도 데이터 일관성이 유지됩니다.
 */
public interface LikeAtomicOperations {

  /** 최대 단일 연산 증가량 (오버플로우 방지) */
  long MAX_INCREMENT_PER_OPERATION = 10_000L;

  /**
   * L1 → L2 원자적 전송
   *
   * <p>로컬 버퍼의 카운트를 Redis Hash와 전역 카운터에 원자적으로 반영합니다.
   *
   * <pre>
   * 수행 연산:
   * 1. HINCRBY {buffer:likes} userIgn count
   * 2. INCRBY {buffer:likes}:total_count count
   * </pre>
   *
   * @param userIgn 사용자 식별자 (not null, not blank)
   * @param count 증가할 값 (1 ~ MAX_INCREMENT_PER_OPERATION)
   * @return true: 성공, false: Redis 장애로 실패 (Graceful Degradation)
   * @throws IllegalArgumentException userIgn이 null/blank이거나 count 범위 초과 시
   */
  boolean atomicTransfer(String userIgn, long count);

  /**
   * Cleanup 후 원자적 삭제 및 카운터 차감 (멱등성 보장)
   *
   * <p>동기화 완료된 엔트리를 임시 키에서 삭제하고 전역 카운터에서 차감합니다. 중복 실행 시 HDEL이 0을 반환하면 DECRBY를 스킵하여 멱등성을 보장합니다.
   *
   * <pre>
   * 수행 연산:
   * 1. deleted = HDEL tempKey userIgn
   * 2. if deleted > 0: DECRBY {buffer:likes}:total_count count
   * </pre>
   *
   * @param tempKey 동기화 임시 키 (not null, not blank)
   * @param userIgn 사용자 식별자 (not null, not blank)
   * @param count 차감할 값 (1 ~ MAX_INCREMENT_PER_OPERATION)
   * @return 삭제된 엔트리 수 (0: 이미 삭제됨/멱등, 1: 정상 삭제)
   * @throws IllegalArgumentException 파라미터 검증 실패 시
   */
  long atomicDeleteAndDecrement(String tempKey, String userIgn, long count);

  /**
   * DB 실패 시 원자적 복구 (Compensation)
   *
   * <p>DB 반영 실패 시 임시 키의 데이터를 원본 버퍼로 복구합니다.
   *
   * <pre>
   * 수행 연산:
   * 1. HINCRBY {buffer:likes} userIgn count
   * 2. HDEL tempKey userIgn
   * </pre>
   *
   * @param tempKey 동기화 임시 키 (not null, not blank)
   * @param userIgn 사용자 식별자 (not null, not blank)
   * @param count 복구할 값 (1 ~ MAX_INCREMENT_PER_OPERATION)
   * @return true: 성공, false: Redis 장애로 실패
   * @throws IllegalArgumentException 파라미터 검증 실패 시
   */
  boolean atomicCompensation(String tempKey, String userIgn, long count);
}
