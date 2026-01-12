package maple.expectation.service.v2.like.dto;

import java.util.Collections;
import java.util.Map;

/**
 * 원자적 fetch 결과 DTO (Immutable Record)
 *
 * <p>금융수준 안전 설계:
 * <ul>
 *   <li>tempKey 보존으로 JVM 크래시 시 복구 가능</li>
 *   <li>불변 Map으로 Thread-Safe 보장</li>
 *   <li>empty() 팩토리 메서드로 null 회피</li>
 * </ul>
 * </p>
 *
 * @param tempKey 임시 키 (복구용 - Hash Tag 패턴: {buffer:likes}:sync:{uuid})
 * @param data    fetch된 데이터 (userIgn -> count)
 */
public record FetchResult(
        String tempKey,
        Map<String, Long> data
) {

    /**
     * Compact Constructor: 불변성 보장
     */
    public FetchResult {
        data = data != null ? Collections.unmodifiableMap(data) : Collections.emptyMap();
    }

    /**
     * 빈 결과 생성 (Empty Object Pattern)
     */
    public static FetchResult empty() {
        return new FetchResult(null, Collections.emptyMap());
    }

    /**
     * 데이터 존재 여부 확인
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * 데이터 건수
     */
    public int size() {
        return data.size();
    }

    /**
     * 총 count 합계 (메트릭용)
     */
    public long totalCount() {
        return data.values().stream().mapToLong(Long::longValue).sum();
    }
}
