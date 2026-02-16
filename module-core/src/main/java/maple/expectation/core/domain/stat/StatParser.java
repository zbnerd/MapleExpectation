package maple.expectation.core.domain.stat;

/** 스탯 파싱 유틸리티 (Pure Domain - Spring/Infrastructure 의존 없음) */
public class StatParser {

  /**
   * 문자열에서 숫자만 추출
   *
   * @param value 파싱할 문자열
   * @return 추출된 숫자 (실패 시 0)
   */
  public int parseNum(String value) {
    if (value == null || value.trim().isEmpty()) {
      return 0;
    }

    try {
      String cleanStr = value.replaceAll("[^0-9\\-]", "");
      return cleanStr.isEmpty() ? 0 : Integer.parseInt(cleanStr);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** 퍼센트(%) 옵션인지 확인 */
  public boolean isPercent(String value) {
    return value != null && value.contains("%");
  }
}
