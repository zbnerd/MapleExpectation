package maple.expectation.service.v2.policy;

import maple.expectation.domain.v2.CubeType;

/**
 * 비용 계산 전략 인터페이스 (Strategy Pattern)
 *
 * <p>OCP (Open-Closed Principle) 준수를 위한 전략 패턴:
 *
 * <ul>
 *   <li>확장에는 열려 있음: 새로운 비용 계산 전략 추가 가능
 *   <li>수정에는 닫혀 있음: 기존 코드 수정 불필요
 * </ul>
 *
 * <p>사용 예시:
 *
 * <pre>{@code
 * // 1. 기본 전략 (하드코딩된 테이블)
 * CostCalculationStrategy defaultStrategy = new TableBasedCostStrategy();
 *
 * // 2. 동적 전략 (DB/설정에서 로드)
 * CostCalculationStrategy dynamicStrategy = new DynamicCostStrategy(repository);
 *
 * // 3. 캐싱 전략
 * CostCalculationStrategy cachedStrategy = new CachedCostStrategy(delegate);
 * }</pre>
 */
@FunctionalInterface
public interface CostCalculationStrategy {

  /**
   * 큐브 비용을 계산합니다.
   *
   * @param type 큐브 타입 (BLACK, RED, ADDITIONAL)
   * @param level 장비 레벨
   * @param grade 잠재력 등급 (한글: "레어", "에픽", "유니크", "레전더리")
   * @return 큐브 1회 사용 비용
   * @throws IllegalArgumentException 유효하지 않은 파라미터인 경우
   */
  long calculateCost(CubeType type, int level, String grade);
}
