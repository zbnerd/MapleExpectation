package maple.expectation.monitoring.collector;

import java.util.Map;

/**
 * 메트릭 수집 전략 인터페이스 (Strategy 패턴)
 *
 * <h3>Issue #251: AI SRE 모니터링</h3>
 *
 * <p>11개 카테고리별 메트릭 수집기를 Strategy 패턴으로 분리하여 OCP 준수.
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 4 (SOLID): Strategy 패턴으로 OCP 준수
 *   <li>Section 6 (Design Patterns): 의존성 역전 (DIP)
 * </ul>
 *
 * @see MetricCategory
 * @see <a href="docs/02_Technical_Guides/lock-strategy.md">Lock Strategy Guide</a>
 */
public interface MetricsCollectorStrategy {

  /**
   * 카테고리 이름 반환
   *
   * @return 카테고리 키 (예: "golden-signals", "jvm", "database")
   */
  String getCategoryName();

  /**
   * 해당 카테고리의 메트릭 수집
   *
   * @return 수집된 메트릭 맵 (key: 메트릭명, value: 값)
   */
  Map<String, Object> collect();

  /**
   * 해당 카테고리 지원 여부 확인
   *
   * @param category 확인할 카테고리
   * @return 지원 여부
   */
  boolean supports(MetricCategory category);

  /**
   * 수집 우선순위 반환 (낮을수록 먼저 수집)
   *
   * @return 우선순위 (기본값: 100)
   */
  default int getOrder() {
    return 100;
  }
}
