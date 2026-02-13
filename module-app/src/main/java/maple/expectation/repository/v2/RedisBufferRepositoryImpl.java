package maple.expectation.repository.v2;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.repository.RedisBufferRepository;
import maple.expectation.infrastructure.redis.script.LuaScripts;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis 버퍼 저장소 구현체
 *
 * <p>좋아요 동기화를 위한 전역 카운터 관리를 담당합니다.
 *
 * <h2>키 구조 (Hash Tag로 CROSSSLOT 방지) - PR #175, #164 수정</h2>
 *
 * <pre>
 * {buffer:likes}              - Hash (사용자별 카운트)
 * {buffer:likes}:total_count  - String (전역 대기 카운트)
 * </pre>
 *
 * @see LuaScripts.Keys 키 상수 정의
 */
@Repository
@RequiredArgsConstructor
public class RedisBufferRepositoryImpl implements RedisBufferRepository {

  private final StringRedisTemplate redisTemplate;

  /**
   * 전역 카운터 증가 (L1 -> L2 전송 시 호출)
   *
   * <p>Note: 원자적 연산은 {@link maple.expectation.global.redis.script.LikeAtomicOperations}를 사용합니다. 이
   * 메서드는 모니터링/디버깅 용도로 유지됩니다.
   */
  public void incrementGlobalCount(long delta) {
    redisTemplate.opsForValue().increment(LuaScripts.Keys.TOTAL_COUNT, delta);
  }

  /**
   * 전역 카운터 감소 (L2 -> L3 동기화 성공 시 호출)
   *
   * <p>Note: 원자적 연산은 {@link maple.expectation.global.redis.script.LikeAtomicOperations}를 사용합니다. 이
   * 메서드는 모니터링/디버깅 용도로 유지됩니다.
   */
  public void decrementGlobalCount(long delta) {
    redisTemplate.opsForValue().decrement(LuaScripts.Keys.TOTAL_COUNT, delta);
  }

  /** 전역 카운터 조회 (모니터링용) */
  public long getTotalPendingCount() {
    String count = redisTemplate.opsForValue().get(LuaScripts.Keys.TOTAL_COUNT);
    return (count == null) ? 0L : Long.parseLong(count);
  }

  /** 전역 카운터 조회 및 초기화 (getAndSet 전략용) */
  public long getAndClearGlobalCount() {
    String count = redisTemplate.opsForValue().getAndSet(LuaScripts.Keys.TOTAL_COUNT, "0");
    return (count == null) ? 0L : Long.parseLong(count);
  }
}
