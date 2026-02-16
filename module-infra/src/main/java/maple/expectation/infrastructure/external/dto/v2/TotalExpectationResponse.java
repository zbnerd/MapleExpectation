package maple.expectation.infrastructure.external.dto.v2;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 기대값 계산 결과 응답 DTO
 *
 * <p>Issue #158: Zero-Waste 정책 적용
 *
 * <ul>
 *   <li>@JsonInclude(NON_EMPTY): null/빈 값 제외하여 5KB 압박 완화
 *   <li>NON_DEFAULT 금지: 0이 의미 있는 값일 수 있음
 * </ul>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class TotalExpectationResponse {
  private String userIgn;
  private long totalCost; // 총 기대 비용 (메소)
  private String totalCostText; // "5,300억" 처럼 보기 좋게 (선택)
  private List<ItemExpectation> items; // 각 아이템별 상세 영수증

  @Data
  @Builder
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public static class ItemExpectation {
    private String part; // 부위 (모자)
    private String itemName; // 이름 (에테르넬...)
    private String potential; // 잠재 옵션 (STR 12% | 9% | 9%)
    private long expectedCost; // 이 아이템 하나 만드는 비용
    private String expectedCostText; // "80억" (선택)
    private long expectedCount; // 기대 횟수
  }
}
