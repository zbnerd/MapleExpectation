package maple.expectation.dto;

import lombok.*;
import maple.expectation.util.StatType;

import java.util.ArrayList;
import java.util.List;

/**
 * 큐브 기대값 계산 입력 DTO
 *
 * <p>두 가지 모드를 지원합니다:</p>
 * <ul>
 *   <li><b>기존 모드</b>: options 리스트로 정확한 옵션 조합 지정</li>
 *   <li><b>DP 모드</b>: targetStatType + minTotal로 "21% 이상" 같은 누적 확률 계산</li>
 * </ul>
 *
 * <h3>#240 V4 확장</h3>
 * <ul>
 *   <li>에디셔널 잠재능력 (additionalGrade, additionalOptions)</li>
 *   <li>스타포스 정보 (starforce, starforceScrollFlag)</li>
 *   <li>아이콘 URL (itemIcon)</li>
 *   <li>장비 세부 분류 (itemEquipmentPart)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CubeCalculationInput {
    private int level;              // 장비 레벨 (숫자)
    private String part;            // 장비 부위 (item_equipment_slot)
    private String grade;           // 잠재능력 등급
    private long expectedCost;

    @Builder.Default
    private List<String> options = new ArrayList<>();     // 옵션 3줄 리스트 (기존 방식)
    private String itemName;

    // ========== #240 V4 확장 필드 ==========

    /**
     * 아이템 아이콘 URL
     * <p>예: "https://open.api.nexon.com/static/maplestory/..."</p>
     */
    private String itemIcon;

    /**
     * 장비 세부 분류 (보조무기 분류용)
     * <p>예: "포스실드", "소울링", "모자"</p>
     */
    private String itemEquipmentPart;

    /**
     * 에디셔널 잠재능력 등급
     * <p>예: "레어", "에픽", "유니크", "레전드리"</p>
     */
    private String additionalGrade;

    /**
     * 에디셔널 잠재능력 옵션 3줄
     */
    @Builder.Default
    private List<String> additionalOptions = new ArrayList<>();

    /**
     * 현재 스타포스 수치 (0~25)
     */
    private int starforce;

    /**
     * 놀장(스타포스 스크롤) 사용 여부
     * <p>Nexon API: "사용" / "미사용"</p>
     */
    private String starforceScrollFlag;

    /**
     * 놀장 장비 여부 판별
     * @return true if 놀장 사용
     */
    public boolean isNoljangEquipment() {
        return "사용".equals(starforceScrollFlag);
    }

    // ========== DP 모드용 필드 (신규) ==========

    /**
     * 목표 스탯 타입 (단위 포함)
     * 예: STR_PERCENT, DEX_PERCENT, ALLSTAT_PERCENT
     */
    private StatType targetStatType;

    /**
     * 목표 합계 (%)
     * 예: 21 → "STR 21% 이상" 조건
     */
    private Integer minTotal;

    /**
     * Tail Clamp 활성화 여부
     * true: 상태공간 O(target) 보장 (기본값)
     * false: 전체 상태공간 계산 (디버그/검증용)
     */
    @Builder.Default
    private boolean enableTailClamp = true;

    /**
     * 확률 테이블 버전 (감사/재현성용)
     * 서비스에서 자동 설정됨
     */
    private String probabilityTableVersion;

    // ========== 유효성 검사 메서드 ==========

    /**
     * DP 모드 여부 판별
     *
     * @return targetStatType과 minTotal이 모두 설정되면 true
     */
    public boolean isDpMode() {
        return targetStatType != null && minTotal != null;
    }

    /**
     * DP 모드 필수 필드 검증 (침묵 실패 방지)
     *
     * <p>P0: UNKNOWN 타입 거부 추가 (Fail-Fast)</p>
     *
     * @throws IllegalArgumentException 필수 필드 누락 또는 무효 시
     */
    public void validateForDpMode() {
        if (!isDpMode()) {
            throw new IllegalArgumentException("DP 모드 필수: targetStatType, minTotal");
        }
        // P0: UNKNOWN 타입 거부 (Fail-Fast)
        if (targetStatType == StatType.UNKNOWN) {
            throw new IllegalArgumentException("DP 모드 무효: targetStatType=UNKNOWN");
        }
        if (part == null || grade == null || level <= 0) {
            throw new IllegalArgumentException("분포 조회 필수: part, grade, level");
        }
        // P0: minTotal 범위 검증 (>=0 허용, 상한 제거)
        // target=0은 수학적으로 잘 정의됨 (항상 성공 = 확률 1.0)
        // 상한 제거: HP%, 보공 등 미래 확장 대응
        if (minTotal < 0) {
            throw new IllegalArgumentException("minTotal은 음수일 수 없습니다: " + minTotal);
        }
    }

    /**
     * 기존 방식 유효성 검사 (옵션이 3줄 다 모였는지 등)
     */
    public boolean isReady() {
        // 1. 필수 필드 체크 (부위, 등급)
        if (part == null || grade == null) {
            return false;
        }

        // 2. 옵션 개수 체크
        if (options == null || options.size() != 3) {
            return false;
        }

        // 3. 옵션 내용 체크: 전부 null이거나 빈 문자열이면 계산 불가
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