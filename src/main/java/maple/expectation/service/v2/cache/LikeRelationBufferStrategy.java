package maple.expectation.service.v2.cache;

import java.util.Set;

/**
 * 좋아요 관계 버퍼 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@link LikeRelationBuffer}: In-Memory + Redis 하이브리드 (기존)</li>
 *   <li>{@link maple.expectation.global.queue.like.RedisLikeRelationBuffer}: Redis Only (V5)</li>
 * </ul>
 *
 * <h3>Feature Flag</h3>
 * <pre>
 * app.buffer.redis.enabled=true  → RedisLikeRelationBuffer
 * app.buffer.redis.enabled=false → LikeRelationBuffer (기본)
 * </pre>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): Strategy 패턴으로 OCP 준수</li>
 *   <li>Green (Performance): 구현체별 최적화 가능</li>
 *   <li>Purple (Auditor): 인터페이스 계약으로 동작 보장</li>
 * </ul>
 */
public interface LikeRelationBufferStrategy {

    /**
     * 전략 유형
     */
    enum StrategyType {
        /** In-Memory + Redis 하이브리드 (기존) */
        IN_MEMORY,
        /** Redis Only (V5 Stateless) */
        REDIS
    }

    /**
     * 현재 전략 유형 반환
     */
    StrategyType getType();

    /**
     * 좋아요 관계 추가
     *
     * @param fingerprint 좋아요를 누른 계정의 fingerprint
     * @param targetOcid  대상 캐릭터의 OCID
     * @return true: 신규 추가, false: 중복, null: Redis 장애
     */
    Boolean addRelation(String fingerprint, String targetOcid);

    /**
     * 좋아요 관계 존재 여부 확인
     *
     * @param fingerprint 좋아요를 누른 계정의 fingerprint
     * @param targetOcid  대상 캐릭터의 OCID
     * @return true: 존재, false: 미존재, null: Redis 장애
     */
    Boolean exists(String fingerprint, String targetOcid);

    /**
     * 좋아요 관계 삭제
     *
     * @param fingerprint 좋아요를 누른 계정의 fingerprint
     * @param targetOcid  대상 캐릭터의 OCID
     * @return true: 삭제 성공, false: 미존재
     */
    Boolean removeRelation(String fingerprint, String targetOcid);

    /**
     * DB 동기화 대기 중인 관계 조회 + 제거 (원자적)
     *
     * @param limit 최대 조회 건수
     * @return 대기 중인 관계 Set (fingerprint:targetOcid 형식)
     */
    Set<String> fetchAndRemovePending(int limit);

    /**
     * 관계 키 생성
     * Format: {fingerprint}:{targetOcid}
     */
    String buildRelationKey(String fingerprint, String targetOcid);

    /**
     * 관계 키 파싱
     *
     * @return [fingerprint, targetOcid]
     */
    String[] parseRelationKey(String relationKey);

    /**
     * 전체 관계 수 조회
     */
    int getRelationsSize();

    /**
     * 대기 중인 관계 수 조회
     */
    int getPendingSize();
}
