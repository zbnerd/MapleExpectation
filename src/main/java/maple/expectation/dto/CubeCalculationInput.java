package maple.expectation.dto;

import lombok.*;
import maple.expectation.aop.annotation.TraceLog;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@TraceLog
@NoArgsConstructor  // 1. 기본 생성자 추가 (new CubeCalculationInput() 가능하게 함)
@AllArgsConstructor // 2. @Builder가 작동하려면 전체 생성자도 필요함
public class CubeCalculationInput {
    private int level;              // 장비 레벨 (숫자)
    private String part;            // 장비 부위
    private String grade;           // 잠재능력 등급
    private long expectedCost;

    @Builder.Default
    private List<String> options = new ArrayList<>();     // 옵션 3줄 리스트
    private String itemName;

    // 유효성 검사 (옵션이 3줄 다 모였는지 등)
    public boolean isReady() {
        // 1. 필수 필드 체크 (부위, 등급)
        if (part == null || grade == null) {
            return false;
        }

        // 2. 옵션 개수 체크
        if (options == null || options.size() != 3) {
            return false;
        }

        // 3. ★ [추가] 옵션 내용 체크: 전부 null이거나 빈 문자열이면 계산 불가
        boolean hasValidOption = false;
        for (String opt : options) {
            if (opt != null && !opt.trim().isEmpty() && !"null".equalsIgnoreCase(opt)) {
                hasValidOption = true;
                break;
            }
        }

        return hasValidOption;
    }

}