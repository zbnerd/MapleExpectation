package maple.expectation.service.v2.policy;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.domain.v2.PotentialGrade;
import org.springframework.stereotype.Component;

/**
 * 테이블 기반 비용 계산 전략 (기본 구현)
 *
 * <p>책임 (Single Responsibility Principle):
 *
 * <ul>
 *   <li>정적 테이블에서 비용 조회
 *   <li>EnumMap을 활용한 O(1) 조회 성능
 * </ul>
 *
 * <p>OCP 준수:
 *
 * <ul>
 *   <li>확장: {@link CostCalculationStrategy} 인터페이스 구현
 *   <li>수정 방지: 새로운 큐브 타입 추가 시 기존 코드 수정 불필요
 * </ul>
 */
@Component
public class TableBasedCostStrategy implements CostCalculationStrategy {

  private final Map<CubeType, TreeMap<Integer, EnumMap<PotentialGrade, Long>>> costMasterTable;

  public TableBasedCostStrategy() {
    this.costMasterTable = initializeCostTable();
  }

  @Override
  public long calculateCost(CubeType type, int level, String grade) {
    // Fail-Fast: 잘못된 입력 즉시 예외 (Silent Failure 방지)
    PotentialGrade validGrade = PotentialGrade.fromKorean(grade);

    TreeMap<Integer, EnumMap<PotentialGrade, Long>> typeTable = costMasterTable.get(type);
    if (typeTable == null) {
      throw new IllegalStateException("Unknown CubeType: " + type);
    }

    // 레벨에 맞는 가장 가까운 하위 구간 키를 찾음 (예: 210레벨 -> 200 키 선택)
    Integer levelKey = typeTable.floorKey(level);
    if (levelKey == null) {
      levelKey = typeTable.firstKey();
    }

    // O(1) EnumMap lookup
    return typeTable.get(levelKey).get(validGrade);
  }

  /**
   * 비용 테이블 초기화 (생성자에서 호출)
   *
   * @return 비용 마스터 테이블
   */
  private Map<CubeType, TreeMap<Integer, EnumMap<PotentialGrade, Long>>> initializeCostTable() {
    Map<CubeType, TreeMap<Integer, EnumMap<PotentialGrade, Long>>> table =
        new EnumMap<>(CubeType.class);

    // 1. 블랙큐브(윗잠재) 비용 테이블
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> blackTable = new TreeMap<>();
    blackTable.put(0, createGradeMap(4000000L, 16000000L, 34000000L, 40000000L));
    blackTable.put(160, createGradeMap(4250000L, 17000000L, 36125000L, 42500000L));
    blackTable.put(200, createGradeMap(4500000L, 18000000L, 38250000L, 45000000L));
    blackTable.put(250, createGradeMap(5000000L, 20000000L, 42500000L, 50000000L));
    table.put(CubeType.BLACK, blackTable);

    // 2. 에디셔널큐브(아랫잠재) 비용 테이블
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> addiTable = new TreeMap<>();
    addiTable.put(0, createGradeMap(13000000L, 36400000L, 44200000L, 52000000L));
    addiTable.put(160, createGradeMap(13812500L, 38675000L, 46962500L, 55250000L));
    addiTable.put(200, createGradeMap(14625000L, 40950000L, 49725000L, 58500000L));
    addiTable.put(250, createGradeMap(16250000L, 45500000L, 55250000L, 65000000L));
    table.put(CubeType.ADDITIONAL, addiTable);

    // 3. 레드큐브 - 레벨 구분 없음, 비용 계산 시 1을 반환하여 '개수'로 취급 가능
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> redTable = new TreeMap<>();
    redTable.put(0, createGradeMap(1L, 1L, 1L, 1L));
    table.put(CubeType.RED, redTable);

    return table;
  }

  /** EnumMap 생성 헬퍼 - RARE, EPIC, UNIQUE, LEGENDARY 순서로 비용 매핑 */
  private EnumMap<PotentialGrade, Long> createGradeMap(
      long rare, long epic, long unique, long legendary) {
    EnumMap<PotentialGrade, Long> map = new EnumMap<>(PotentialGrade.class);
    map.put(PotentialGrade.RARE, rare);
    map.put(PotentialGrade.EPIC, epic);
    map.put(PotentialGrade.UNIQUE, unique);
    map.put(PotentialGrade.LEGENDARY, legendary);
    return map;
  }
}
