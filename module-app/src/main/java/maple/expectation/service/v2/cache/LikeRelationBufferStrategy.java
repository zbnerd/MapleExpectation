package maple.expectation.service.v2.cache;

/**
 * 좋아요 관계 버퍼 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>구현체</h3>
 *
 * <ul>
 *   <li>{@link LikeRelationBuffer}: In-Memory + Redis 하이브리드 (기존)
 *   <li>{@link maple.expectation.infrastructure.queue.like.RedisLikeRelationBuffer}: Redis Only
 *       (V5)
 * </ul>
 *
 * <h3>Feature Flag</h3>
 *
 * <pre>
 * app.buffer.redis.enabled=true  → RedisLikeRelationBuffer
 * app.buffer.redis.enabled=false → LikeRelationBuffer (기본)
 * </pre>
 *
 * <h3>DIP 준수 (ADR-014)</h3>
 *
 * <p>이 인터페이스는 {@link maple.expectation.application.port.LikeRelationBufferStrategy}를 확장하여 호환성을
 * 유지합니다. 새로운 코드는 Core 인터페이스를 직접 사용하십시오.
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 OCP 준수
 *   <li>Green (Performance): 구현체별 최적화 가능
 *   <li>Purple (Auditor): 인터페이스 계약으로 동작 보장
 * </ul>
 */
@Deprecated(since = "ADR-014", forRemoval = false)
public interface LikeRelationBufferStrategy
    extends maple.expectation.application.port.LikeRelationBufferStrategy {
  // All methods inherited from CoreLikeRelationBufferStrategy
}
