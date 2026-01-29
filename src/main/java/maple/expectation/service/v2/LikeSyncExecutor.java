package maple.expectation.service.v2;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.global.error.exception.LikeSyncCircuitOpenException;
import maple.expectation.repository.v2.GameCharacterRepository;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 좋아요 동기화 실행기 (Issue #48: Batch Update 지원)
 *
 * <h3>5-Agent Council 합의 사항</h3>
 * <ul>
 *   <li><b>Green</b>: JDBC batchUpdate()로 JPA 대비 10x 성능 향상</li>
 *   <li><b>Blue</b>: SRP 준수 - DB 저장만 담당</li>
 *   <li><b>Red</b>: CircuitBreaker 적용으로 연속 실패 보호</li>
 *   <li><b>Purple</b>: REQUIRES_NEW + READ_COMMITTED으로 트랜잭션 안전성</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeSyncExecutor {

    private final GameCharacterRepository gameCharacterRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 개별 유저의 좋아요 수를 DB에 물리적으로 반영
     *
     * <p>Propagation.REQUIRES_NEW: 각 유저별로 독립된 트랜잭션을 보장하여
     * 한 명이라도 실패해도 다른 유저의 동기화에 영향을 주지 않음.</p>
     *
     * <p>Issue #81 Fix: READ_COMMITTED 격리 수준</p>
     * <ul>
     *   <li>기본값 REPEATABLE_READ에서 발생하는 Gap Lock 제거</li>
     *   <li>동시 UPDATE 시 데드락 방지</li>
     * </ul>
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public void executeIncrement(String userIgn, long count) {
        gameCharacterRepository.incrementLikeCount(userIgn, count);
    }

    /**
     * 청크 단위 Batch Update (Issue #48: Lock Contention 최적화)
     *
     * <h4>성능 개선 (Green 분석)</h4>
     * <ul>
     *   <li>개별 트랜잭션: 10K entries = 10K transactions</li>
     *   <li>Batch Update: 10K entries = 20 transactions (500/청크)</li>
     *   <li>예상 효과: 트랜잭션 수 99.8% 감소, 15x 성능 향상</li>
     * </ul>
     *
     * <h4>CircuitBreaker (Red 요구사항)</h4>
     * <p>연속 실패 시 서킷이 열려 DB 부하를 방지합니다.
     * 실패한 청크는 {@link LikeSyncService#handleChunkFailure}에서 Redis로 복원됩니다.</p>
     *
     * @param entries 청크 내 엔트리 목록 (userIgn → count)
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    @CircuitBreaker(name = "likeSyncDb", fallbackMethod = "batchFallback")
    public void executeIncrementBatch(List<Map.Entry<String, Long>> entries) {
        if (entries.isEmpty()) return;

        String sql = "UPDATE game_character SET like_count = like_count + ? WHERE user_ign = ?";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<String, Long> entry = entries.get(i);
                ps.setLong(1, entry.getValue());
                ps.setString(2, entry.getKey());
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });

        log.debug("Batch update completed: {} entries", entries.size());
    }

    /**
     * CircuitBreaker Fallback (서킷 오픈 시)
     *
     * <p>서킷이 열리면 예외를 던져 상위 레이어에서 보상 트랜잭션이 실행되도록 합니다.</p>
     */
    @SuppressWarnings("unused")  // CircuitBreaker fallback으로 사용됨
    private void batchFallback(List<Map.Entry<String, Long>> entries, Throwable t) {
        log.warn("[LikeSync] Circuit OPEN, batch skipped ({} entries): {}",
                entries.size(), t.getMessage());
        throw new LikeSyncCircuitOpenException(t);
    }
}
