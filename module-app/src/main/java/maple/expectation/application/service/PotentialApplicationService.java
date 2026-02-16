package maple.expectation.application.service;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.calculator.PotentialCalculator;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.domain.stat.StatType;
import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;

/**
 * 잠재력 계산 Application Service
 *
 * <p>Core의 PotentialCalculator를 조립하여 비즈니스 유스케이스 수행
 *
 * <p><b>아키텍처 계층 구조:</b>
 *
 * <pre>
 * App Layer (이 클래스)     : 유스케이스 조립, Flow 제어
 *    ↓ 사용
 * Core Layer (Calculator)   : 순수 계산 로직 (의존성 없음)
 *    ↓ 조회
 * Port (CubeRatePort)       : 인터페이스 (DIP)
 *    ↓ 구현
 * Infra Layer (Adapter)     : DB 조회, 외부 API
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PotentialApplicationService {

  private final CubeRatePort cubeRatePort;
  private final PotentialCalculator potentialCalculator;
  private final LogicExecutor executor;

  /**
   * 잠재력 옵션 3개를 받아 스탯 합계 계산
   *
   * <p>Core 계산기에 순수 로직만 위임하고, 예외 처리/메트릭은 LogicExecutor가 담당
   *
   * @param option1 옵션 1 (예: "STR +12%")
   * @param option2 옵션 2 (예: "올스탯 +9%")
   * @param option3 옵션 3 (예: "보스 공격력 데미지 +30%")
   * @return 스탯 타입별 합계 (예: {STR: 12, ALL_STAT: 9, BOSS_DAMAGE: 30})
   */
  public Map<StatType, Integer> calculateMainPotential(
      String option1, String option2, String option3) {
    return executor.executeOrDefault(
        () -> potentialCalculator.calculateMainPotential(option1, option2, option3),
        Map.of(),
        TaskContext.of("PotentialApplicationService", "CalculateMainPotential"));
  }

  /**
   * 에디셔널 잠재력 옵션 3개를 받아 스탯 합계 계산
   *
   * @param option1 옵션 1
   * @param option2 옵션 2
   * @param option3 옵션 3
   * @return 스탯 타입별 합계
   */
  public Map<StatType, Integer> calculateAdditionalPotential(
      String option1, String option2, String option3) {
    return executor.executeOrDefault(
        () -> potentialCalculator.calculateAdditionalPotential(option1, option2, option3),
        Map.of(),
        TaskContext.of("PotentialApplicationService", "CalculateAdditionalPotential"));
  }

  /**
   * 특정 스탯의 유효 수치 계산 (올스탯 포함)
   *
   * <p>예: STR 30 + 올스탯 12 = STR 42
   *
   * @param stats 스탯 맵
   * @param type 조회할 스탯 타입
   * @return 유효 수치 (올스탯 반영)
   */
  public int getEffectiveStat(Map<StatType, Integer> stats, StatType type) {
    return executor.executeOrDefault(
        () -> potentialCalculator.getEffectiveStat(stats, type),
        0,
        TaskContext.of("PotentialApplicationService", "GetEffectiveStat", type.name()));
  }

  /**
   * 큐브 확률 데이터 조회 예시 (Port → Adapter 통합 테스트)
   *
   * <p>이 메서드는 Port 인터페이스를 통해 Infrastructure Layer의 데이터를 조회하는 DIP(Dependency Inversion Principle) 적용
   * 예시입니다.
   *
   * @param cubeType 큐브 타입 (BLACK, RED, ADDITIONAL)
   * @return 해당 큐브 타입의 확률 데이터 목록
   */
  public java.util.List<CubeRate> getCubeRates(CubeType cubeType) {
    return executor.executeOrDefault(
        () -> cubeRatePort.findByCubeType(cubeType),
        java.util.List.of(),
        TaskContext.of("PotentialApplicationService", "GetCubeRates", cubeType.name()));
  }

  /**
   * 잠재력 계산 통합 예시
   *
   * <p>1. 큐브 확률 조회 2. 잠재력 계산 3. 유효 스탯 추출
   *
   * @param option1 옵션 1
   * @param option2 옵션 2
   * @param option3 옵션 3
   * @param targetStat 목표 스탯 타입
   * @return 목표 스탯의 최종 수치
   */
  public int calculateFinalStat(
      String option1, String option2, String option3, StatType targetStat) {
    return executor.executeOrDefault(
        () -> {
          // 1. 순수 계산 로직 (Core)
          Map<StatType, Integer> stats =
              potentialCalculator.calculateMainPotential(option1, option2, option3);

          // 2. 유효 수치 계산 (Core)
          return potentialCalculator.getEffectiveStat(stats, targetStat);
        },
        0,
        TaskContext.of("PotentialApplicationService", "CalculateFinalStat", targetStat.name()));
  }
}
