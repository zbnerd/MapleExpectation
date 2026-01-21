package maple.expectation.repository.v2;

import maple.expectation.domain.v2.EquipmentExpectationSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
