package maple.expectation.repository.v2;

import jakarta.persistence.LockModeType;
import maple.expectation.aop.annotation.TraceLog;
import maple.expectation.domain.v2.GameCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 게임 캐릭터 Repository
 *
 * <h3>락 전략 (Issue #28)</h3>
 * <ul>
 *   <li>일반 조회: 락 없음 (읽기 전용, @Version으로 충분)</li>
 *   <li>수정 조회: PESSIMISTIC_WRITE (동시 수정 방지)</li>
 *   <li>좋아요 증가: Atomic Update (Hot Row 최적화)</li>
 * </ul>
 *
 * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide</a>
 */
public interface GameCharacterRepository extends JpaRepository<GameCharacter, Long> {

    /**
     * 일반 조회 (락 없음)
     *
     * <p><b>락 전략</b>: 없음</p>
     * <p><b>선택 사유</b>: 읽기 전용 조회이므로 락 불필요. @Version으로 수정 시 충돌 감지.</p>
     *
     * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - 캐릭터 도메인</a>
     */
    Optional<GameCharacter> findByUserIgn(String userIgn);

    /**
     * 비관적 락 조회 (수정용)
     *
     * <p><b>락 전략</b>: PESSIMISTIC_WRITE</p>
     * <p><b>선택 사유</b>: 캐릭터 정보 수정은 드물어 락 경합 낮음.
     * 데이터 무결성이 성능보다 중요한 케이스.</p>
     *
     * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - 캐릭터 수정 시 동시 업데이트 방지</a>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM GameCharacter c WHERE c.userIgn = :userIgn")
    Optional<GameCharacter> findByUserIgnWithPessimisticLock(@Param("userIgn") String userIgn);

    /**
     * 좋아요 카운트 원자적 증가
     *
     * <p><b>락 전략</b>: Atomic Update (SET col = col + n)</p>
     * <p><b>선택 사유</b>: Hot Row에서 Pessimistic Lock은 대기열 병목 발생.
     * Atomic Update는 DB가 내부적으로 Row Lock → 즉시 해제하므로 고처리량 보장.
     * MySQL InnoDB는 단일 UPDATE 문에 대해 원자성 보장.</p>
     *
     * <p><b>호출 컨텍스트</b>: LikeSyncScheduler에서 3초 주기로 Redis 버퍼 데이터를 DB에 반영</p>
     *
     * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide - 좋아요 도메인</a>
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE GameCharacter c SET c.likeCount = c.likeCount + :count WHERE c.userIgn = :userIgn")
    void incrementLikeCount(@Param("userIgn") String userIgn, @Param("count") Long count);

    /**
     * 단일 캐릭터 상세 조회 (캐릭터 + 장비 정보 한방에)
     * 상세 페이지나 장비 계산 로직에서 사용
     */
    @TraceLog
    @Query("SELECT gc FROM GameCharacter gc LEFT JOIN FETCH gc.equipment WHERE gc.userIgn = :userIgn")
    Optional<GameCharacter> findByUserIgnWithEquipment(@Param("userIgn") String userIgn);
}