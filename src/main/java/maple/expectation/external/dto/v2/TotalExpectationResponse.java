package maple.expectation.external.dto.v2;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class TotalExpectationResponse {
    private String nickname;
    private long totalCost; // 총 기대 비용 (메소)
    private String totalCostText; // "5,300억" 처럼 보기 좋게 (선택)
    private List<ItemExpectation> items; // 각 아이템별 상세 영수증

    @Data
    @Builder
    public static class ItemExpectation {
        private String part;        // 부위 (모자)
        private String itemName;    // 이름 (에테르넬...)
        private String potential;   // 잠재 옵션 (STR 12% | 9% | 9%)
        private long expectedCost;  // 이 아이템 하나 만드는 비용
        private String expectedCostText; // "80억" (선택)
    }
}