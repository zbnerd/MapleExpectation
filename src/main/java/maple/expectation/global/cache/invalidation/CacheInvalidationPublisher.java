package maple.expectation.global.cache.invalidation;

/**
 * 캐시 무효화 이벤트 발행 인터페이스 (Strategy Pattern)
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>TieredCache의 evict()/clear() 호출 시 다른 인스턴스의 L1 캐시 무효화 이벤트 발행
 *
 * <h3>CLAUDE.md Section 4: Strategy Pattern</h3>
 *
 * <p>Pub/Sub 구현체 교체 가능 (Redis RTopic → Kafka 등)
 *
 * @see CacheInvalidationEvent
 */
public interface CacheInvalidationPublisher {

  /**
   * 캐시 무효화 이벤트 발행
   *
   * @param event 발행할 무효화 이벤트
   */
  void publish(CacheInvalidationEvent event);
}
