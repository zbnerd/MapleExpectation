package maple.expectation.service.v2.cache;

import java.util.Map;

/**
 * 좋아요 카운터 버퍼 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <p>좋아요 증분(delta)을 버퍼링하는 전략을 정의합니다.
 * In-Memory (Caffeine) 또는 Redis 구현체를 Feature Flag로 교체 가능합니다.</p>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 OCP 준수</li>
 *   <li>Green (Performance): 인터페이스 추상화로 성능 오버헤드 최소화</li>
 *   <li>Red (SRE): Feature Flag로 운영 중 전환 가능</li>
 *   <li>Purple (Auditor): 기존 API 100% 호환</li>
 * </ul>
 *
 * @see LikeBufferStorage In-Memory Caffeine 구현
 * @see maple.expectation.global.queue.like.RedisLikeBufferStorage Redis 구현
 */
public interface LikeBufferStrategy {

    /**
     * 좋아요 증분 (원자적)
     *
     * @param userIgn 대상 사용자 IGN
     * @param delta   증분값 (양수: 좋아요, 음수: 취소)
     * @return 증분 후 값, 실패 시 null
     */
    Long increment(String userIgn, long delta);

    /**
     * 현재 카운터 조회
     *
     * @param userIgn 대상 사용자 IGN
     * @return 현재 delta 값, 없으면 0, 장애 시 null
     */
    Long get(String userIgn);

    /**
     * 모든 카운터 조회 (Flush용)
     *
     * @return userIgn → delta 맵
     */
    Map<String, Long> getAllCounters();

    /**
     * 원자적 fetch + clear (Flush용)
     *
     * @param limit 최대 조회 개수
     * @return userIgn → delta 맵
     */
    Map<String, Long> fetchAndClear(int limit);

    /**
     * 버퍼 크기 조회
     *
     * @return 버퍼 내 엔트리 수
     */
    int getBufferSize();

    /**
     * 전략 타입
     *
     * @return IN_MEMORY 또는 REDIS
     */
    StrategyType getType();

    enum StrategyType {
        IN_MEMORY,
        REDIS
    }
}
