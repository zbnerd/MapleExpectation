package maple.expectation.application.service;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.service.v2.starforce.StarforceLookupTable;
import org.springframework.stereotype.Service;

/**
 * 스타포스 강화 계산 Application Service
 *
 * <p>StarforceLookupTable을 조립하여 스타포스 기대 비용 계산 유스케이스 수행
 *
 * <p><b>아키텍처 계층 구조:</b>
 *
 * <pre>
 * App Layer (이 클래스)     : 유스케이스 조립, Flow 제어
 *    ↓ 사용
 * Infra Layer (LookupTable): 인메모리 Lookup Table (O(1) 조회)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarforceApplicationService {

  private final StarforceLookupTable starforceLookupTable;
  private final LogicExecutor executor;

  /**
   * 기대 비용 계산 (기본 옵션)
   *
   * <p>기본 옵션: 스타캐치 O, 썬데이메이플 O, 30% 할인 O, 파괴방지 X
   *
   * @param currentStar 현재 스타포스 (0~30)
   * @param targetStar 목표 스타포스 (currentStar ~ 30)
   * @param itemLevel 아이템 레벨 (1~300)
   * @return 기대 비용 (메소)
   */
  public BigDecimal calculateExpectedCost(int currentStar, int targetStar, int itemLevel) {
    return executor.executeOrDefault(
        () -> starforceLookupTable.getExpectedCost(currentStar, targetStar, itemLevel),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService",
            "CalculateExpectedCost",
            currentStar + "->" + targetStar));
  }

  /**
   * 레벨별 최대 스타포스 조회
   *
   * @param itemLevel 아이템 레벨
   * @return 최대 스타포스
   */
  public int getMaxStarForLevel(int itemLevel) {
    return executor.executeOrDefault(
        () -> starforceLookupTable.getMaxStarForLevel(itemLevel),
        0,
        TaskContext.of(
            "StarforceApplicationService", "GetMaxStarForLevel", String.valueOf(itemLevel)));
  }

  /**
   * 성공 확률 조회
   *
   * @param currentStar 현재 스타포스 (0~24)
   * @return 성공 확률 (0.0 ~ 1.0)
   */
  public BigDecimal getSuccessProbability(int currentStar) {
    return executor.executeOrDefault(
        () -> starforceLookupTable.getSuccessProbability(currentStar),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService", "GetSuccessProbability", String.valueOf(currentStar)));
  }

  /**
   * 파괴 확률 조회
   *
   * @param currentStar 현재 스타포스 (0~24)
   * @return 파괴 확률 (0.0 ~ 1.0)
   */
  public BigDecimal getDestroyProbability(int currentStar) {
    return executor.executeOrDefault(
        () -> starforceLookupTable.getDestroyProbability(currentStar),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService", "GetDestroyProbability", String.valueOf(currentStar)));
  }

  /**
   * 단일 강화 비용 조회
   *
   * @param currentStar 현재 스타포스 (0~24)
   * @param itemLevel 아이템 레벨
   * @return 1회 강화 비용 (메소)
   */
  public BigDecimal getSingleEnhanceCost(int currentStar, int itemLevel) {
    return executor.executeOrDefault(
        () -> starforceLookupTable.getSingleEnhanceCost(currentStar, itemLevel),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService", "GetSingleEnhanceCost", currentStar + ":" + itemLevel));
  }

  /**
   * 옵션별 기대 비용 계산
   *
   * @param currentStar 현재 스타
   * @param targetStar 목표 스타
   * @param itemLevel 아이템 레벨
   * @param useStarCatch 스타캐치 사용 여부
   * @param useSundayMaple 썬데이메이플 적용 여부
   * @param useDiscount 30% 할인 적용 여부
   * @param useDestroyPrevention 파괴방지 사용 여부
   * @return 기대 비용
   */
  public BigDecimal calculateExpectedCostWithOptions(
      int currentStar,
      int targetStar,
      int itemLevel,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDiscount,
      boolean useDestroyPrevention) {
    return executor.executeOrDefault(
        () ->
            starforceLookupTable.getExpectedCost(
                currentStar,
                targetStar,
                itemLevel,
                useStarCatch,
                useSundayMaple,
                useDiscount,
                useDestroyPrevention),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService",
            "CalculateExpectedCostWithOptions",
            currentStar + "->" + targetStar));
  }

  /**
   * 기대 파괴 횟수 계산
   *
   * @param currentStar 현재 스타포스
   * @param targetStar 목표 스타포스
   * @param useStarCatch 스타캐치 사용 여부
   * @param useSundayMaple 썬데이메이플 적용 여부
   * @param useDestroyPrevention 파괴방지 사용 여부
   * @return 기대 파괴 횟수
   */
  public BigDecimal calculateExpectedDestroyCount(
      int currentStar,
      int targetStar,
      boolean useStarCatch,
      boolean useSundayMaple,
      boolean useDestroyPrevention) {
    return executor.executeOrDefault(
        () ->
            starforceLookupTable.getExpectedDestroyCount(
                currentStar, targetStar, useStarCatch, useSundayMaple, useDestroyPrevention),
        BigDecimal.ZERO,
        TaskContext.of(
            "StarforceApplicationService",
            "CalculateExpectedDestroyCount",
            currentStar + "->" + targetStar));
  }

  /**
   * 초기화 완료 여부 조회
   *
   * @return true if initialized
   */
  public boolean isInitialized() {
    return executor.executeOrDefault(
        starforceLookupTable::isInitialized,
        false,
        TaskContext.of("StarforceApplicationService", "IsInitialized"));
  }
}
