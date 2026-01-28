package maple.expectation.service.v2.like.realtime.dto;

import java.io.Serializable;
import java.time.Instant;

/**
 * 좋아요 이벤트 DTO (Scale-out Pub/Sub)
 *
 * <h3>Issue #278: Scale-out 환경 실시간 좋아요 동기화</h3>
 * <p>인스턴스 간 L1 캐시 무효화를 위한 이벤트 메시지</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Record로 불변성 보장</li>
 *   <li>Green (Performance): 최소한의 필드로 네트워크 부하 최소화</li>
 *   <li>Purple (Data): eventType으로 LIKE/UNLIKE 구분</li>
 * </ul>
 *
 * @param userIgn    대상 캐릭터 닉네임 (캐시 키)
 * @param newDelta   버퍼의 새 delta 값 (HINCRBY 반환값)
 * @param eventType  이벤트 타입 (LIKE, UNLIKE)
 * @param sourceInstanceId 이벤트 발행 인스턴스 ID (Self-skip용)
 * @param timestamp  이벤트 발생 시각 (디버깅/메트릭용)
 */
public record LikeEvent(
        String userIgn,
        long newDelta,
        EventType eventType,
        String sourceInstanceId,
        Instant timestamp
) implements Serializable {

    /**
     * 좋아요 이벤트 타입
     */
    public enum EventType {
        LIKE,
        UNLIKE
    }

    /**
     * LIKE 이벤트 생성 팩토리 메서드
     */
    public static LikeEvent like(String userIgn, long newDelta, String instanceId) {
        return new LikeEvent(userIgn, newDelta, EventType.LIKE, instanceId, Instant.now());
    }

    /**
     * UNLIKE 이벤트 생성 팩토리 메서드
     */
    public static LikeEvent unlike(String userIgn, long newDelta, String instanceId) {
        return new LikeEvent(userIgn, newDelta, EventType.UNLIKE, instanceId, Instant.now());
    }
}
