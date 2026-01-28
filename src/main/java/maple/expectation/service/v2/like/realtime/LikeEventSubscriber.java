package maple.expectation.service.v2.like.realtime;

import maple.expectation.service.v2.like.realtime.dto.LikeEvent;

/**
 * 좋아요 이벤트 구독 인터페이스 (Strategy Pattern)
 *
 * <h3>Issue #278: Scale-out 환경 실시간 좋아요 동기화</h3>
 * <p>다른 인스턴스에서 발행한 이벤트를 수신하여 L1 캐시 무효화</p>
 *
 * <h3>CLAUDE.md Section 4: Strategy Pattern</h3>
 * <p>Pub/Sub 구현체 교체 가능 (Redis RTopic → Kafka 등)</p>
 */
public interface LikeEventSubscriber {

    /**
     * 이벤트 구독 시작
     * <p>Bean 초기화 시 자동 호출 (@PostConstruct)</p>
     */
    void subscribe();

    /**
     * 이벤트 처리 (내부 콜백)
     *
     * @param event 수신된 이벤트
     */
    void onEvent(LikeEvent event);

    /**
     * 구독 해제
     * <p>애플리케이션 종료 시 호출 (@PreDestroy)</p>
     */
    void unsubscribe();
}
