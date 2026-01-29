package maple.expectation.repository.v2;

import maple.expectation.domain.v2.EquipmentExpectationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 장비 기대값 요약 Repository (#240)
 *
 * @see EquipmentExpectationSummary 연관 엔티티
 */
public interface EquipmentExpectationSummaryRepository extends JpaRepository<EquipmentExpectationSummary, Long> {

    /**
     * 기대값 요약 Upsert (동시성 안전) (#262)
     *
     * <h3>Issue #262: Cache Stampede 해결</h3>
     * <p>MySQL `INSERT ... ON DUPLICATE KEY UPDATE`로 동시 쓰기 Race Condition 제거</p>
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
