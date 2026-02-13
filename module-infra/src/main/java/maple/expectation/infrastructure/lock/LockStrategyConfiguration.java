package maple.expectation.infrastructure.lock;

import jakarta.annotation.PostConstruct;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Lock Strategy 활성화 상태 로깅
 *
 * <h3>Phase 1: Feature Flag Implementation</h3>
 *
 * <p>애플리케이션 시작 시 활성화된 락 전략을 로깅하여 설정이 올바르게 적용되었는지 확인합니다.
 *
 * <h3>로그 메시지 예시</h3>
 *
 * <ul>
 *   <li>Redis: ✅ [Lock Strategy] Redis 분산 락 활성화 (Redisson)
 *   <li>MySQL: ✅ [Lock Strategy] MySQL Named Lock 활성화
 *   <li>Resilient: ✅ [Lock Strategy] Redis → MySQL Fallback 활성화
 * </ul>
 *
 * <h3>조건부 로딩 검증</h3>
 *
 * <p>@ConditionalOnProperty 어노테이션이 설정값에 따라 빈 로딩을 올바르게 제어하는지 검증합니다.
 *
 * <h3>P1 Fix: 생성자 주입 (CLAUDE.md Section 6)</h3>
 *
 * <p>@Autowired(required=false) 필드 주입 -> Optional 생성자 주입
 *
 * @see RedisDistributedLockStrategy
 * @see MySqlNamedLockStrategy
 * @see ResilientLockStrategy
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LockStrategyConfiguration {

  private final Optional<RedisDistributedLockStrategy> redisLockStrategy;
  private final Optional<MySqlNamedLockStrategy> mysqlLockStrategy;
  private final Optional<ResilientLockStrategy> resilientLockStrategy;

  /**
   * 애플리케이션 시작 시 활성화된 락 전략 로깅
   *
   * <p>Phase 1 구현 목표:
   *
   * <ul>
   *   <li>✅ lock.impl 프로퍼티 값 확인 (redis/mysql)
   *   <li>✅ 각 전략의 조건부 로딩 상태 로깅
   *   <li>✅ 기본값: Redis 전략 활성화
   *   <li>✅ MySQL 선택 시 MySQL 전략 활성화
   * </ul>
   */
  @PostConstruct
  public void logActiveLockStrategy() {
    String lockImpl = System.getProperty("lock.impl", "redis");

    if (resilientLockStrategy.isPresent()) {
      log.info("✅ [Lock Strategy] Redis → MySQL Fallback 활성화 (ResilientLockStrategy)");
      log.info("   - Primary: Redisson RLock (Watchdog 모드)");
      log.info("   - Fallback: MySQL Named Lock (세션 기반)");
      log.info("   - Circuit Breaker: redisLock 인스턴스 적용");
    } else if (mysqlLockStrategy.isPresent()) {
      log.info("✅ [Lock Strategy] MySQL Named Lock 활성화 (MySqlNamedLockStrategy)");
      log.info("   - 구현: GET_LOCK/RELEASE_LOCK (세션 고정)");
      log.info("   - 주의: tryLockImmediately() 지원 불가 → executeWithLock() 사용");
    } else if (redisLockStrategy.isPresent()) {
      log.info("✅ [Lock Strategy] Redis 분산 락 활성화 (RedisDistributedLockStrategy)");
      log.info("   - 구현: Redisson RLock (Watchdog 자동 갱신)");
      log.info("   - 장점: 고성능, 저지연 (< 1ms), TTL 자동 관리");
    } else {
      log.warn("⚠️ [Lock Strategy] 활성화된 락 전략 없음 - 애플리케이션 기능 제한됨");
    }

    log.info("   - 설정값: lock.impl={}", lockImpl);
    log.info("   - 기본값: redis (matchIfMissing=true)");
  }
}
