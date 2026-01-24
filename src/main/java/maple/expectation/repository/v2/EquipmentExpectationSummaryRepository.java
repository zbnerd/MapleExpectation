package maple.expectation.repository.v2;

import maple.expectation.domain.v2.EquipmentExpectationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 장비 기대값 요약 Repository (#240)
 *
 * <h3>조회 패턴</h3>
 * <ul>
 *   <li>캐릭터 ID + 프리셋 번호: 단일 기대값 조회</li>
 *   <li>캐릭터 ID: 모든 프리셋 기대값 조회</li>
 * </ul>
 *
 * @see EquipmentExpectationSummary 연관 엔티티
 */
public interface EquipmentExpectationSummaryRepository extends JpaRepository<EquipmentExpectationSummary, Long> {

    /**
     * 특정 캐릭터의 특정 프리셋 기대값 조회
     *
     * @param gameCharacterId 캐릭터 ID
     * @param presetNo 프리셋 번호 (1, 2, 3)
     * @return 기대값 요약 (없으면 Optional.empty())
     */
    Optional<EquipmentExpectationSummary> findByGameCharacterIdAndPresetNo(Long gameCharacterId, Integer presetNo);

    /**
     * 특정 캐릭터의 모든 프리셋 기대값 조회
     *
     * @param gameCharacterId 캐릭터 ID
     * @return 모든 프리셋 기대값 목록
     */
    List<EquipmentExpectationSummary> findAllByGameCharacterId(Long gameCharacterId);

    /**
     * 특정 캐릭터의 기대값 존재 여부 확인
     *
     * @param gameCharacterId 캐릭터 ID
     * @return 기대값 존재 여부
     */
    boolean existsByGameCharacterId(Long gameCharacterId);

    /**
     * 특정 캐릭터의 모든 기대값 삭제 (재계산 전)
     *
     * @param gameCharacterId 캐릭터 ID
     */
    void deleteAllByGameCharacterId(Long gameCharacterId);

    /**
     * 캐릭터 IGN으로 프리셋별 기대값 조회 (Join 쿼리)
     *
     * <p>V4 API에서 IGN으로 직접 조회할 때 사용</p>
     *
     * @param userIgn 캐릭터 IGN
     * @return 모든 프리셋 기대값 목록
     */
    @Query("""
            SELECT ees FROM EquipmentExpectationSummary ees
            JOIN GameCharacter gc ON gc.id = ees.gameCharacterId
            WHERE gc.userIgn = :userIgn
            ORDER BY ees.presetNo
            """)
    List<EquipmentExpectationSummary> findAllByUserIgn(@Param("userIgn") String userIgn);

    /**
     * 캐릭터 IGN + 프리셋 번호로 기대값 조회 (Join 쿼리)
     *
     * @param userIgn 캐릭터 IGN
     * @param presetNo 프리셋 번호
     * @return 기대값 요약
     */
    @Query("""
            SELECT ees FROM EquipmentExpectationSummary ees
            JOIN GameCharacter gc ON gc.id = ees.gameCharacterId
            WHERE gc.userIgn = :userIgn AND ees.presetNo = :presetNo
            """)
    Optional<EquipmentExpectationSummary> findByUserIgnAndPresetNo(
            @Param("userIgn") String userIgn,
            @Param("presetNo") Integer presetNo);

    /**
     * 기대값 요약 Upsert (동시성 안전) (#262)
     *
     * <h3>Issue #262: Cache Stampede 해결</h3>
     * <p>MySQL `INSERT ... ON DUPLICATE KEY UPDATE`로 동시 쓰기 Race Condition 제거</p>
     *
     * <h3>동작 방식</h3>
     * <ul>
     *   <li>새 레코드: INSERT 수행</li>
     *   <li>기존 레코드: UPDATE 수행 (version 증가 없이 덮어쓰기)</li>
     *   <li>동시 요청: MySQL이 row-level lock으로 순차 처리</li>
     * </ul>
     *
     * <p>Unique Key: (game_character_id, preset_no)</p>
     *
     * @param gameCharacterId 캐릭터 ID
     * @param presetNo 프리셋 번호
     * @param totalExpectedCost 총 기대 비용
     * @param blackCubeCost 블랙큐브 비용
     * @param redCubeCost 레드큐브 비용
     * @param additionalCubeCost 에디셔널큐브 비용
     * @param starforceCost 스타포스 비용
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO equipment_expectation_summary
                (game_character_id, preset_no, total_expected_cost, black_cube_cost,
                 red_cube_cost, additional_cube_cost, starforce_cost, calculated_at, version)
            VALUES
                (:gameCharacterId, :presetNo, :totalExpectedCost, :blackCubeCost,
                 :redCubeCost, :additionalCubeCost, :starforceCost, NOW(), 0)
            ON DUPLICATE KEY UPDATE
                total_expected_cost = :totalExpectedCost,
                black_cube_cost = :blackCubeCost,
                red_cube_cost = :redCubeCost,
                additional_cube_cost = :additionalCubeCost,
                starforce_cost = :starforceCost,
                calculated_at = NOW()
            """, nativeQuery = true)
    void upsertExpectationSummary(
            @Param("gameCharacterId") Long gameCharacterId,
            @Param("presetNo") Integer presetNo,
            @Param("totalExpectedCost") BigDecimal totalExpectedCost,
            @Param("blackCubeCost") BigDecimal blackCubeCost,
            @Param("redCubeCost") BigDecimal redCubeCost,
            @Param("additionalCubeCost") BigDecimal additionalCubeCost,
            @Param("starforceCost") BigDecimal starforceCost);
}
