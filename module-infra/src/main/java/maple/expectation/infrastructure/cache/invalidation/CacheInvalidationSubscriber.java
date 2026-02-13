package maple.expectation.infrastructure.cache.invalidation;

/**
 * 캐시 무효화 이벤트 구독 인터페이스 (Strategy Pattern)
 *
 * <h3>Issue #278: Scale-out 환경 L1 Cache Coherence</h3>
 *
 * <p>다른 인스턴스에서 발행한 캐시 무효화 이벤트를 수신하여 L1(Caffeine) 캐시 무효화
 *
 * <h3>CLAUDE.md Section 4: Strategy Pattern</h3>
 *
 * <p>Pub/Sub 구현체 교체 가능 (Redis RTopic → Kafka 등)
 *
 * @see CacheInvalidationEvent
 */
public interface CacheInvalidationSubscriber {

  /**
   * 이벤트 구독 시작
   *
   * <p>Bean 초기화 시 자동 호출 (@PostConstruct)
   */
  void subscribe();

  /**
   * 이벤트 처리 (내부 콜백)
   *
   * @param event 수신된 무효화 이벤트
   */
  void onEvent(CacheInvalidationEvent event);

  /**
   * 구독 해제
   *
   * <p>애플리케이션 종료 시 호출 (@PreDestroy)
   */
  void unsubscribe();
}
