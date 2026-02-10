package maple.expectation.service.v4.buffer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;

/**
 * Expectation Write-Behind 버퍼용 DTO (#266)
 *
 * <h3>5-Agent Council 합의</h3>
 *
 * <ul>
 *   <li>Purple (Auditor): Record로 immutability 보장
 *   <li>Green (Performance): BigDecimal 정밀도 유지
 * </ul>
 *
 * <h3>용도</h3>
 *
 * <p>V4 기대값 계산 결과를 메모리 버퍼에 저장하고, 스케줄러가 배치로 DB에 동기화할 때 사용됩니다.
 *
 * @param characterId 캐릭터 ID
 * @param presetNo 프리셋 번호 (1, 2, 3)
 * @param totalExpectedCost 총 기대 비용
 * @param blackCubeCost 블랙큐브 비용
 * @param redCubeCost 레드큐브 비용
 * @param additionalCubeCost 에디셔널큐브 비용
 * @param starforceCost 스타포스 비용
 * @param createdAt 생성 시각
 */
public record ExpectationWriteTask(
    Long characterId,
    Integer presetNo,
    BigDecimal totalExpectedCost,
    BigDecimal blackCubeCost,
    BigDecimal redCubeCost,
    BigDecimal additionalCubeCost,
    BigDecimal starforceCost,
    LocalDateTime createdAt) {

  /**
   * PresetExpectation으로부터 Write Task 생성
   *
   * @param characterId 캐릭터 ID
   * @param preset 프리셋 기대값
   * @return ExpectationWriteTask
   */
  public static ExpectationWriteTask from(Long characterId, PresetExpectation preset) {
    return new ExpectationWriteTask(
        characterId,
        preset.getPresetNo(),
        preset.getTotalExpectedCost(),
        preset.getCostBreakdown().getBlackCubeCost(),
        preset.getCostBreakdown().getRedCubeCost(),
        preset.getCostBreakdown().getAdditionalCubeCost(),
        preset.getCostBreakdown().getStarforceCost(),
        LocalDateTime.now());
  }

  /**
   * 버퍼 키 생성 (중복 방지용)
   *
   * <p>동일 캐릭터의 동일 프리셋은 Latest-wins 전략으로 덮어쓰기
   *
   * @return "{characterId}:{presetNo}" 형식의 키
   */
  public String key() {
    return characterId + ":" + presetNo;
  }
}
