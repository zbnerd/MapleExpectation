package maple.expectation.dto.v4;

import lombok.Builder;
import lombok.Getter;
import maple.expectation.dto.CubeCalculationInput;

import java.util.List;

/**
 * V4 장비 기대값 계산 입력 DTO (#240)
 *
 * <h3>기존 CubeCalculationInput과의 차이</h3>
 * <ul>
 *   <li>스타포스 정보 포함 (currentStar, targetStar)</li>
 *   <li>에디셔널 잠재능력 정보 포함</li>
 *   <li>프리셋 번호 포함</li>
 * </ul>
 */
@Getter
@Builder
public class EquipmentCalculationInput {

    // ==================== 기본 정보 ====================

    private final String itemName;
    private final String itemPart;  // 장착 부위 (모자, 상의 등)
    private final int itemLevel;
    private final int presetNo;     // 프리셋 번호 (1, 2, 3)

    // ==================== 윗잠재 (메인 잠재능력) ====================

    private final String potentialGrade;  // 잠재능력 등급 (레어, 에픽, 유니크, 레전드리)
    private final List<String> potentialOptions;

    // ==================== 아랫잠재 (에디셔널 잠재능력) ====================

    private final String additionalPotentialGrade;
    private final List<String> additionalPotentialOptions;

    // ==================== 스타포스 ====================

    private final int currentStar;  // 현재 스타포스 (0~25)
    private final int targetStar;   // 목표 스타포스

    // ==================== 유틸리티 메서드 ====================

    /**
     * 윗잠재 정보 존재 여부
     */
    public boolean hasPotential() {
        return potentialGrade != null && !potentialGrade.isEmpty();
    }

    /**
     * 아랫잠재 정보 존재 여부
     */
    public boolean hasAdditionalPotential() {
        return additionalPotentialGrade != null && !additionalPotentialGrade.isEmpty();
    }

    /**
     * 스타포스 정보 존재 여부
     */
    public boolean hasStarforce() {
        return currentStar < targetStar;
    }

    /**
     * 윗잠재용 CubeCalculationInput 변환
     */
    public CubeCalculationInput toPotentialCubeInput() {
        CubeCalculationInput input = new CubeCalculationInput();
        input.setItemName(itemName);
        input.setPart(itemPart);
        input.setLevel(itemLevel);
        input.setGrade(potentialGrade);
        if (potentialOptions != null) {
            input.getOptions().addAll(potentialOptions);
        }
        return input;
    }

    /**
     * 아랫잠재용 CubeCalculationInput 변환
     */
    public CubeCalculationInput toAdditionalCubeInput() {
        CubeCalculationInput input = new CubeCalculationInput();
        input.setItemName(itemName);
        input.setPart(itemPart);
        input.setLevel(itemLevel);
        input.setGrade(additionalPotentialGrade);
        if (additionalPotentialOptions != null) {
            input.getOptions().addAll(additionalPotentialOptions);
        }
        return input;
    }

    /**
     * 계산 준비 완료 여부
     */
    public boolean isReady() {
        return itemName != null && !itemName.isEmpty() && itemLevel > 0;
    }
}
