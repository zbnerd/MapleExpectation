package maple.expectation.service.v2.policy;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import maple.expectation.domain.v2.CubeType;
import maple.expectation.domain.v2.PotentialGrade;
import org.springframework.stereotype.Component;

/**
 * 큐브 비용 정책
 *
 * <p>큐브 타입, 장비 레벨, 잠재능력 등급에 따른 비용을 조회합니다. 잘못된 등급 입력 시 Silent Failure(0원 반환) 대신 명시적 예외를 발생시킵니다.
 *
 * <p><b>Performance (Green Agent):</b> 이중 EnumMap 구조로 O(1) 조회 보장. 외부: EnumMap&lt;CubeType&gt;, 내부:
 * EnumMap&lt;PotentialGrade&gt;
 *
 * @see PotentialGrade
 */
@Component
public class CubeCostPolicy {
  private static final Map<CubeType, TreeMap<Integer, EnumMap<PotentialGrade, Long>>>
      COST_MASTER_TABLE = new EnumMap<>(CubeType.class);

  static {
    // 1. 블랙큐브(윗잠재) 비용 테이블 - 이미지 상단 표 반영
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> blackTable = new TreeMap<>();
    blackTable.put(0, createGradeMap(4000000L, 16000000L, 34000000L, 40000000L));
    blackTable.put(160, createGradeMap(4250000L, 17000000L, 36125000L, 42500000L));
    blackTable.put(200, createGradeMap(4500000L, 18000000L, 38250000L, 45000000L));
    blackTable.put(250, createGradeMap(5000000L, 20000000L, 42500000L, 50000000L));
    COST_MASTER_TABLE.put(CubeType.BLACK, blackTable);

    // 2. 에디셔널큐브(아랫잠재) 비용 테이블 - 이미지 하단 표 반영
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> addiTable = new TreeMap<>();
    addiTable.put(0, createGradeMap(13000000L, 36400000L, 44200000L, 52000000L));
    addiTable.put(160, createGradeMap(13812500L, 38675000L, 46962500L, 55250000L));
    addiTable.put(200, createGradeMap(14625000L, 40950000L, 49725000L, 58500000L));
    addiTable.put(250, createGradeMap(16250000L, 45500000L, 55250000L, 65000000L));
    COST_MASTER_TABLE.put(CubeType.ADDITIONAL, addiTable);

    // 3. 레드큐브 - 레벨 구분 없음, 비용 계산 시 1을 반환하여 '개수'로 취급 가능하게 설정
    // 나중에 Service/Decorator에서 여기에 시세를 곱하는 로직을 추가하면 됩니다.
    TreeMap<Integer, EnumMap<PotentialGrade, Long>> redTable = new TreeMap<>();
    redTable.put(0, createGradeMap(1L, 1L, 1L, 1L));
    COST_MASTER_TABLE.put(CubeType.RED, redTable);
  }

  /** EnumMap 생성 헬퍼 - RARE, EPIC, UNIQUE, LEGENDARY 순서로 비용 매핑 */
  private static EnumMap<PotentialGrade, Long> createGradeMap(
      long rare, long epic, long unique, long legendary) {
    EnumMap<PotentialGrade, Long> map = new EnumMap<>(PotentialGrade.class);
    map.put(PotentialGrade.RARE, rare);
    map.put(PotentialGrade.EPIC, epic);
    map.put(PotentialGrade.UNIQUE, unique);
    map.put(PotentialGrade.LEGENDARY, legendary);
    return map;
  }

  /**
   * 큐브 비용을 조회합니다.
   *
   * @param type 큐브 타입 (BLACK, RED, ADDITIONAL)
   * @param level 장비 레벨
   * @param grade 잠재능력 등급 (한글: "레어", "에픽", "유니크", "레전드리")
   * @return 큐브 1회 사용 비용
   * @throws maple.expectation.global.error.exception.InvalidPotentialGradeException 유효하지 않은 등급명인 경우
   * @throws IllegalStateException 유효하지 않은 CubeType인 경우
   */
  public long getCubeCost(CubeType type, int level, String grade) {
    // Fail-Fast: 잘못된 입력 즉시 예외 (Silent Failure 방지)
    PotentialGrade validGrade = PotentialGrade.fromKorean(grade);

    TreeMap<Integer, EnumMap<PotentialGrade, Long>> typeTable = COST_MASTER_TABLE.get(type);
    if (typeTable == null) {
      throw new IllegalStateException("Unknown CubeType: " + type);
    }

    // 레벨에 맞는 가장 가까운 하위 구간 키를 찾음 (예: 210레벨 -> 200 키 선택)
    Integer levelKey = typeTable.floorKey(level);
    if (levelKey == null) {
      levelKey = typeTable.firstKey();
    }

    // O(1) EnumMap lookup - String 기반 조회보다 효율적
    return typeTable.get(levelKey).get(validGrade);
  }
}
