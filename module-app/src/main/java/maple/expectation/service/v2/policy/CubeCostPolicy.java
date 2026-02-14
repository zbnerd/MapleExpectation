package maple.expectation.service.v2.policy;

import lombok.RequiredArgsConstructor;
import maple.expectation.domain.v2.CubeType;
import org.springframework.stereotype.Component;

/**
 * 큐브 비용 정책 (Facade)
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>비용 계산 전략 선택 및 위임
 *   <li>하위 호환성 유지 (기존 코드 호환)
 * </ul>
 *
 * <p>OCP (Open-Closed Principle) 준수:
 *
 * <ul>
 *   <li>확장: {@link CostCalculationStrategy} 구현체로 새로운 계산 방식 추가
 *   <li>수정 방지: 전략 교체로 동작 변경 (코드 수정 불필요)
 * </ul>
 *
 * <p>리팩토링 내역:
 *
 * <ul>
 *   <li>hard-coded 테이블을 {@link TableBasedCostStrategy}로 분리
 *   <li>전략 패턴 도입으로 동적 전략 교체 가능
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CubeCostPolicy {

  private final CostCalculationStrategy costStrategy;

  /**
   * 큐브 비용을 조회합니다.
   *
   * @param type 큐브 타입 (BLACK, RED, ADDITIONAL)
   * @param level 장비 레벨
   * @param grade 잠재력 등급 (한글: "레어", "에픽", "유니크", "레전더리")
   * @return 큐브 1회 사용 비용
   * @throws maple.expectation.error.exception.InvalidPotentialGradeException 유효하지 않은 등급명인 경우
   * @throws IllegalStateException 유효하지 않은 CubeType인 경우
   */
  public long getCubeCost(CubeType type, int level, String grade) {
    return costStrategy.calculateCost(type, level, grade);
  }
}
