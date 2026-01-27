package maple.expectation.service.v2.shutdown;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence Tracker 전략 인터페이스 (#271 V5 Stateless Architecture)
 *
 * <h3>역할</h3>
 * <p>비동기 저장 작업 추적 로직의 인터페이스를 정의합니다.
 * Feature Flag에 따라 In-Memory 또는 Redis 구현체로 교체됩니다.</p>
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>{@link EquipmentPersistenceTracker}: In-Memory 기반 (단일 인스턴스)</li>
 *   <li>{@link maple.expectation.global.queue.persistence.RedisEquipmentPersistenceTracker}: Redis 기반 (Scale-out)</li>
 * </ul>
 *
 * <h3>5-Agent Council 합의</h3>
 * <ul>
 *   <li>Blue (Architect): 전략 패턴으로 인프라 변경 최소화</li>
 *   <li>Green (Performance): 단일 인스턴스에서는 In-Memory가 더 빠름</li>
 *   <li>Red (SRE): Feature Flag로 운영 중 롤백 가능</li>
 *   <li>Yellow (QA): 테스트에서 In-Memory 사용으로 외부 의존성 제거</li>
 * </ul>
 *
 * @see EquipmentPersistenceTracker In-Memory 구현체
 * @see maple.expectation.global.queue.persistence.RedisEquipmentPersistenceTracker Redis 구현체
 */
public interface PersistenceTrackerStrategy {

    /**
     * 비동기 저장 작업 추적 등록
     *
     * @param ocid   캐릭터 OCID
     * @param future 비동기 작업 Future
     * @throws IllegalStateException Shutdown 진행 중인 경우
     */
    void trackOperation(String ocid, CompletableFuture<Void> future);

    /**
     * 모든 작업 완료 대기 (Shutdown용)
     *
     * @param timeout 최대 대기 시간
     * @return true: 모든 작업 완료, false: 타임아웃 또는 Shutdown 중
     */
    boolean awaitAllCompletion(Duration timeout);

    /**
     * Pending OCID 목록 조회
     *
     * @return pending OCID 리스트
     */
    List<String> getPendingOcids();

    /**
     * Pending 작업 수 조회
     *
     * @return pending 작업 수
     */
    int getPendingCount();

    /**
     * 테스트용 리셋
     */
    void resetForTesting();

    /**
     * 현재 전략 유형 반환
     *
     * @return 전략 유형 (IN_MEMORY 또는 REDIS)
     */
    StrategyType getType();

    /**
     * Persistence Tracker 전략 유형
     */
    enum StrategyType {
        /** In-Memory ConcurrentHashMap 기반 (단일 인스턴스) */
        IN_MEMORY,
        /** Redis SET 기반 (Scale-out 지원) */
        REDIS
    }
}
