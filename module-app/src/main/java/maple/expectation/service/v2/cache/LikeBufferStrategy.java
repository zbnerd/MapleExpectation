package maple.expectation.service.v2.cache;

/**
 * 좋아요 카운터 버퍼 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 *
 * <p>좋아요 증분(delta)을 버퍼링하는 전략을 정의합니다. In-Memory (Caffeine) 또는 Redis 구현체를 Feature Flag로 교체 가능합니다.
 *
 * <h3>DIP 준수 (ADR-014)</h3>
 *
 * <p>이 인터페이스는 {@link maple.expectation.application.port.LikeBufferStrategy}를 확장하여 호환성을 유지합니다. 새로운
 * 코드는 Core 인터페이스를 직접 사용하십시오.
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 OCP 준수
 *   <li>Green (Performance): 인터페이스 추상화로 성능 오버헤드 최소화
 *   <li>Red (SRE): Feature Flag로 운영 중 전환 가능
 *   <li>Purple (Auditor): 기존 API 100% 호환
 * </ul>
 *
 * @see LikeBufferStorage In-Memory Caffeine 구현
 * @see maple.expectation.infrastructure.queue.like.RedisLikeBufferStorage Redis 구현
 * @deprecated {@link maple.expectation.application.port.LikeBufferStrategy}를 직접 사용하십시오
 */
@Deprecated(since = "ADR-014", forRemoval = false)
public interface LikeBufferStrategy extends maple.expectation.application.port.LikeBufferStrategy {
  // All methods inherited from CoreLikeBufferStrategy
}
