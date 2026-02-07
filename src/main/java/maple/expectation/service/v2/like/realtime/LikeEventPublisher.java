package maple.expectation.service.v2.like.realtime;

import maple.expectation.service.v2.like.realtime.dto.LikeEvent;

/**
 * 좋아요 이벤트 발행 인터페이스 (Strategy Pattern)
 *
 * <h3>Issue #278: Scale-out 환경 실시간 좋아요 동기화</h3>
 *
 * <p>인스턴스 간 L1 캐시 무효화 이벤트 발행
 *
 * <h3>CLAUDE.md Section 4: Strategy Pattern</h3>
 *
 * <p>Pub/Sub 구현체 교체 가능 (Redis RTopic → Kafka 등)
 */
public interface LikeEventPublisher {

  /**
   * 좋아요 이벤트 발행
   *
   * @param event 발행할 이벤트
   */
  void publish(LikeEvent event);

  /**
   * 좋아요 추가 이벤트 발행 (편의 메서드)
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @param newDelta 버퍼의 새 delta 값
   */
  void publishLike(String userIgn, long newDelta);

  /**
   * 좋아요 취소 이벤트 발행 (편의 메서드)
   *
   * @param userIgn 대상 캐릭터 닉네임
   * @param newDelta 버퍼의 새 delta 값
   */
  void publishUnlike(String userIgn, long newDelta);
}
