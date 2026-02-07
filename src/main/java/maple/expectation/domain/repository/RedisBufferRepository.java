package maple.expectation.domain.repository;

/**
 * Redis 버퍼 저장소 인터페이스
 *
 * <p>좋아요 동기화를 위한 전역 카운터 관리를 담당합니다.
 *
 * <p>키 구조 (Hash Tag로 CROSSSLOT 방지):
 *
 * <ul>
 *   <li>{buffer:likes} - Hash (사용자별 카운터)
 *   <li>{buffer:likes}:total_count - String (전역 대기 카운트)
 * </ul>
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@link maple.expectation.repository.v2.RedisBufferRepositoryImpl} - Redis 기반 구현
 * </ul>
 */
public interface RedisBufferRepository {

  /**
   * 전역 카운터 증가 (L1 -> L2 전송 시 호출)
   *
   * <p>Note: 원자적 연산은 {@link maple.expectation.global.redis.script.LikeAtomicOperations}를
   * 사용합니다.
   *
   * @param delta 증가량
   */
  void incrementGlobalCount(long delta);

  /**
   * 전역 카운터 감소 (L2 -> L3 동기화 성공 시 호출)
   *
   * <p>Note: 원자적 연산은 {@link maple.expectation.global.redis.script.LikeAtomicOperations}를
   * 사용합니다.
   *
   * @param delta 감소량
   */
  void decrementGlobalCount(long delta);

  /**
   * 전역 카운터 조회 (모니터링용)
   *
   * @return 전역 대기 카운트
   */
  long getTotalPendingCount();

  /**
   * 전역 카운터 조회 및 초기화 (getAndSet 전략용)
   *
   * @return 이전 카운트 값, 초기화된 경우 0
   */
  long getAndClearGlobalCount();
}
