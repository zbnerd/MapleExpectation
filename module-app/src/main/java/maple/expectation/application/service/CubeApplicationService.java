package maple.expectation.application.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.calculator.CubeRateCalculator;
import maple.expectation.core.domain.model.CubeRate;
import maple.expectation.core.domain.model.CubeType;
import maple.expectation.core.port.out.CubeRatePort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.stereotype.Service;

/**
 * 큐브 확률 계산 Application Service
 *
 * <p>Core의 CubeRateCalculator를 조립하여 큐브 옵션 확률 계산 유스케이스 수행
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
public class CubeApplicationService {

  private final CubeRatePort cubeRatePort;
  private final CubeRateCalculator cubeRateCalculator;
  private final LogicExecutor executor;

  /**
   * 특정 옵션의 등장 확률 계산
   *
   * <p>큐브 종류, 레벨, 부위, 등급, 슬롯에 따른 옵션 등장 확률을 반환합니다.
   *
   * @param cubeType 큐브 종류 (BLACK, RED, ADDITIONAL)
   * @param level 큐브 레벨
   * @param part 장비 부위
   * @param grade 등급 (RARE, EPIC, UNIQUE, LEGEND)
   * @param slot 슬롯 번호 (1, 2, 3)
   * @param optionName 옵션 이름 (예: "STR +12%")
   * @return 등장 확률 (0.0 ~ 1.0)
   */
  public double calculateOptionRate(
      CubeType cubeType, int level, String part, String grade, int slot, String optionName) {
    return executor.executeOrDefault(
        () -> {
          List<CubeRate> rates = cubeRatePort.findByCubeType(cubeType);
          return cubeRateCalculator.getOptionRate(
              cubeType, level, part, grade, slot, optionName, rates);
        },
        0.0,
        TaskContext.of("CubeApplicationService", "CalculateOptionRate", optionName));
  }

  /**
   * 큐브 확률 데이터 조회 (Port → Adapter 통합 테스트)
   *
   * <p>이 메서드는 Port 인터페이스를 통해 Infrastructure Layer의 데이터를 조회하는 DIP(Dependency Inversion Principle) 적용
   * 예시입니다.
   *
   * @param cubeType 큐브 타입 (BLACK, RED, ADDITIONAL)
   * @return 해당 큐브 타입의 확률 데이터 목록
   */
  public List<CubeRate> getCubeRates(CubeType cubeType) {
    return executor.executeOrDefault(
        () -> cubeRatePort.findByCubeType(cubeType),
        List.of(),
        TaskContext.of("CubeApplicationService", "GetCubeRates", cubeType.name()));
  }
}
