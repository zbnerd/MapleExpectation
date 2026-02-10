package maple.expectation.service.v4.persistence;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.dto.v4.EquipmentExpectationResponseV4.PresetExpectation;
import maple.expectation.repository.v2.EquipmentExpectationSummaryRepository;
import maple.expectation.service.v4.buffer.ExpectationWriteBackBuffer;
import org.springframework.stereotype.Component;

/**
 * 기대값 영속성 서비스 (P1-5: God Class 분해)
 *
 * <h3>책임</h3>
 *
 * <ul>
 *   <li>Write-Behind 버퍼를 통한 비동기 DB 저장
 *   <li>백프레셔 발생 시 동기 폴백
 *   <li>Upsert 패턴으로 동시성 안전 보장
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpectationPersistenceService {

  private final ExpectationWriteBackBuffer writeBackBuffer;
  private final EquipmentExpectationSummaryRepository summaryRepository;

  /**
   * 결과 저장 - Write-Behind 버퍼 적용 (#266 P1-3)
   *
   * <p>백프레셔 발생 시 동기 DB 저장으로 폴백
   *
   * @param characterId 캐릭터 ID
   * @param presets 프리셋 결과 목록
   */
  public void saveResults(Long characterId, List<PresetExpectation> presets) {
    boolean buffered = writeBackBuffer.offer(characterId, presets);

    if (buffered) {
      log.debug(
          "[V4] Write-Behind 버퍼에 저장: characterId={}, presets={}", characterId, presets.size());
      return;
    }

    log.warn("[V4] Buffer backpressure - fallback to sync save: characterId={}", characterId);
    saveResultsSync(characterId, presets);
  }

  /**
   * 결과 동기 DB 저장 - Upsert 패턴 (#262)
   *
   * <p>MySQL `INSERT ... ON DUPLICATE KEY UPDATE`로 Race Condition 제거
   */
  public void saveResultsSync(Long characterId, List<PresetExpectation> presets) {
    for (PresetExpectation preset : presets) {
      summaryRepository.upsertExpectationSummary(
          characterId,
          preset.getPresetNo(),
          preset.getTotalExpectedCost(),
          preset.getCostBreakdown().getBlackCubeCost(),
          preset.getCostBreakdown().getRedCubeCost(),
          preset.getCostBreakdown().getAdditionalCubeCost(),
          preset.getCostBreakdown().getStarforceCost());
    }
    log.debug("[V4] 동기 DB 저장 완료: characterId={}, presets={}", characterId, presets.size());
  }
}
