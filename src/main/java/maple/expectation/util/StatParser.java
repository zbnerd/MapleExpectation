package maple.expectation.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StatParser {

    /**
     * 문자열에서 숫자만 추출합니다.
     * 예: "STR +12%" -> 12
     * 예: "450" -> 450
     * 예: "+450" -> 450
     * 예: null or "" -> 0
     */
    public static int parseNum(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }

        try {
            // 1. 정규식: 숫자(0-9)와 마이너스(-) 빼고 다 지워라
            // 예: "STR +12%" -> "12"
            // 예: "쿨타임 -2초" -> "-2"
            String cleanStr = value.replaceAll("[^0-9\\-]", "");

            if (cleanStr.isEmpty()) {
                return 0;
            }

            return Integer.parseInt(cleanStr);
        } catch (NumberFormatException e) {
            log.warn("숫자 파싱 실패 (기본값 0 반환): {}", value);
            return 0;
        }
    }
    
    /**
     * 퍼센트(%) 옵션인지 확인합니다. (잠재능력 계산용)
     */
    public static boolean isPercent(String value) {
        return value != null && value.contains("%");
    }
}