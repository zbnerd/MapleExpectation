package maple.expectation.service.v2.shutdown;

/**
 * Persistence Tracker 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 *
 * <p>비동기 저장 작업 추적 로직의 인터페이스를 정의합니다. Feature Flag에 따라 In-Memory 또는 Redis 구현체로 교체됩니다.
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>{@link EquipmentPersistenceTracker}: In-Memory 기반 (단일 인스턴스)
 *   <li>{@link
 *       maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker}: Redis
 *       기반 (Scale-out)
 * </ul>
 *
 * <h3>DIP 준수 (ADR-014)</h3>
 *
 * <p>이 인터페이스는 {@link maple.expectation.application.port.PersistenceTrackerStrategy}를 확장하여 호환성을
 * 유지합니다. 새로운 코드는 Core 인터페이스를 직접 사용하십시오.
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): 전략 패턴으로 인프라 변경 최소화
 *   <li>Green (Performance): 단일 인스턴스에서는 In-Memory가 더 빠름
 *   <li>Red (SRE): Feature Flag로 운영 중 롤백 가능
 *   <li>Yellow (QA): 테스트에서 In-Memory 사용으로 외부 의존성 제거
 * </ul>
 *
 * @see EquipmentPersistenceTracker In-Memory 구현체
 * @see maple.expectation.infrastructure.queue.persistence.RedisEquipmentPersistenceTracker Redis
 *     구현체
 */
@Deprecated(since = "ADR-014", forRemoval = false)
public interface PersistenceTrackerStrategy
    extends maple.expectation.application.port.PersistenceTrackerStrategy {
  // All methods inherited from CorePersistenceTrackerStrategy
}
