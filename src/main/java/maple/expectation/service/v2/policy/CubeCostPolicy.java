package maple.expectation.service.v2.policy;

import maple.expectation.domain.v2.CubeType;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

@Component
public class CubeCostPolicy {
    private static final Map<CubeType, TreeMap<Integer, Map<String, Long>>> COST_MASTER_TABLE = new EnumMap<>(CubeType.class);

    static {
        // 1. 블랙큐브(윗잠재) 비용 테이블 - 이미지 상단 표 반영
        TreeMap<Integer, Map<String, Long>> blackTable = new TreeMap<>();
        blackTable.put(0,   Map.of("레어", 4000000L, "에픽", 16000000L, "유니크", 34000000L, "레전드리", 40000000L));
        blackTable.put(160, Map.of("레어", 4250000L, "에픽", 17000000L, "유니크", 36125000L, "레전드리", 42500000L));
        blackTable.put(200, Map.of("레어", 4500000L, "에픽", 18000000L, "유니크", 38250000L, "레전드리", 45000000L));
        blackTable.put(250, Map.of("레어", 5000000L, "에픽", 20000000L, "유니크", 42500000L, "레전드리", 50000000L));
        COST_MASTER_TABLE.put(CubeType.BLACK, blackTable);

        // 2. 에디셔널큐브(아랫잠재) 비용 테이블 - 이미지 하단 표 반영
        TreeMap<Integer, Map<String, Long>> addiTable = new TreeMap<>();
        addiTable.put(0,   Map.of("레어", 13000000L, "에픽", 36400000L, "유니크", 44200000L, "레전드리", 52000000L));
        addiTable.put(160, Map.of("레어", 13812500L, "에픽", 38675000L, "유니크", 46962500L, "레전드리", 55250000L));
        addiTable.put(200, Map.of("레어", 14625000L, "에픽", 40950000L, "유니크", 49725000L, "레전드리", 58500000L));
        addiTable.put(250, Map.of("레어", 16250000L, "에픽", 45500000L, "유니크", 55250000L, "레전드리", 65000000L));
        COST_MASTER_TABLE.put(CubeType.ADDITIONAL, addiTable);

        // 3. 레드큐브 - 레벨 구분 없음, 비용 계산 시 1을 반환하여 '개수'로 취급 가능하게 설정
        // 나중에 Service/Decorator에서 여기에 시세를 곱하는 로직을 추가하면 됩니다.
        TreeMap<Integer, Map<String, Long>> redTable = new TreeMap<>();
        redTable.put(0, Map.of("레어", 1L, "에픽", 1L, "유니크", 1L, "레전드리", 1L));
        COST_MASTER_TABLE.put(CubeType.RED, redTable);
    }

    public long getCubeCost(CubeType type, int level, String grade) {
        TreeMap<Integer, Map<String, Long>> typeTable = COST_MASTER_TABLE.get(type);
        if (typeTable == null) return 0L;

        // 레벨에 맞는 가장 가까운 하위 구간 키를 찾음 (예: 210레벨 -> 200 키 선택)
        Integer levelKey = typeTable.floorKey(level);
        if (levelKey == null) levelKey = typeTable.firstKey();

        return typeTable.get(levelKey).getOrDefault(grade, 0L);
    }
}