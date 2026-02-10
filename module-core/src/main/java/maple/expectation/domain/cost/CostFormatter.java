package maple.expectation.domain.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 비용 포맷터 (#240 V4)
 *
 * <h3>한국식 금액 표기 유틸리티</h3>
 *
 * <p>BigDecimal 금액을 "조/억/만" 단위로 변환합니다.
 *
 * <h3>예시</h3>
 *
 * <ul>
 *   <li>12345678900000 → "12조 3456억 7890만"
 *   <li>500000000000 → "5000억"
 *   <li>50000000 → "5000만"
 *   <li>1234567 → "123만"
 * </ul>
 *
 * <h3>메이플스토리 금액 특성</h3>
 *
 * <ul>
 *   <li>일반적으로 만 메소 단위 이상의 금액 처리
 *   <li>억 단위는 큐브 비용에서 흔함
 *   <li>조 단위는 스타포스 22성 이상에서 발생
 * </ul>
 */
public final class CostFormatter {

  private static final BigDecimal JO = new BigDecimal("1000000000000"); // 조 (10^12)
  private static final BigDecimal EOK = new BigDecimal("100000000"); // 억 (10^8)
  private static final BigDecimal MAN = new BigDecimal("10000"); // 만 (10^4)

  private CostFormatter() {
    // Utility class - no instantiation
  }

  /**
   * BigDecimal 금액을 한국식 표기로 포맷
   *
   * @param cost 금액 (메소)
   * @return 포맷된 문자열 (e.g., "12조 3456억 7890만")
   */
  public static String format(BigDecimal cost) {
    if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
      return "0";
    }

    StringBuilder sb = new StringBuilder();
    BigDecimal remaining = cost.setScale(0, RoundingMode.HALF_UP);

    // 조 단위
    if (remaining.compareTo(JO) >= 0) {
      BigDecimal jo = remaining.divide(JO, 0, RoundingMode.DOWN);
      sb.append(jo.toPlainString()).append("조 ");
      remaining = remaining.remainder(JO);
    }

    // 억 단위
    if (remaining.compareTo(EOK) >= 0) {
      BigDecimal eok = remaining.divide(EOK, 0, RoundingMode.DOWN);
      sb.append(eok.toPlainString()).append("억 ");
      remaining = remaining.remainder(EOK);
    }

    // 만 단위
    if (remaining.compareTo(MAN) >= 0) {
      BigDecimal man = remaining.divide(MAN, 0, RoundingMode.DOWN);
      sb.append(man.toPlainString()).append("만");
    }

    String result = sb.toString().trim();
    return result.isEmpty() ? "0" : result;
  }

  /**
   * long 금액을 한국식 표기로 포맷
   *
   * @param cost 금액 (메소)
   * @return 포맷된 문자열
   */
  public static String format(long cost) {
    return format(BigDecimal.valueOf(cost));
  }

  /**
   * 간략화된 표기 (가장 큰 단위만)
   *
   * <p>예: 12345678900000 → "12조"
   *
   * @param cost 금액 (메소)
   * @return 간략화된 문자열
   */
  public static String formatCompact(BigDecimal cost) {
    if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
      return "0";
    }

    cost = cost.setScale(0, RoundingMode.HALF_UP);

    if (cost.compareTo(JO) >= 0) {
      BigDecimal jo = cost.divide(JO, 1, RoundingMode.HALF_UP);
      return jo.stripTrailingZeros().toPlainString() + "조";
    }

    if (cost.compareTo(EOK) >= 0) {
      BigDecimal eok = cost.divide(EOK, 1, RoundingMode.HALF_UP);
      return eok.stripTrailingZeros().toPlainString() + "억";
    }

    if (cost.compareTo(MAN) >= 0) {
      BigDecimal man = cost.divide(MAN, 1, RoundingMode.HALF_UP);
      return man.stripTrailingZeros().toPlainString() + "만";
    }

    return cost.toPlainString();
  }

  /**
   * 정확한 숫자 표기 (천 단위 콤마)
   *
   * @param cost 금액 (메소)
   * @return 콤마 포맷 문자열 (e.g., "12,345,678,900,000")
   */
  public static String formatWithComma(BigDecimal cost) {
    if (cost == null) {
      return "0";
    }

    return String.format("%,d", cost.setScale(0, RoundingMode.HALF_UP).longValue());
  }
}
