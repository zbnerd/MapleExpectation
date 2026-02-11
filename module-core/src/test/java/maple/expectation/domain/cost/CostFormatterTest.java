package maple.expectation.domain.cost;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * CostFormatter 순수 유닛 테스트
 *
 * <p>테스트 피라미드 최하위 계층: Spring/DB 없이 순수 JUnit5 + AssertJ
 *
 * <p>테스트 속도: ~1ms (완전히 격리된 유틸리티 테스트)
 */
@DisplayName("CostFormatter 순수 유닛 테스트")
class CostFormatterTest {

  @Test
  @DisplayName("0 또는 음수는 '0'을 반환해야 함")
  void zero_or_negative_returns_zero() {
    assertThat(CostFormatter.format(BigDecimal.ZERO)).isEqualTo("0");
    assertThat(CostFormatter.format(BigDecimal.valueOf(-1))).isEqualTo("0");
    assertThat(CostFormatter.format(null)).isEqualTo("0");
    assertThat(CostFormatter.format(0L)).isEqualTo("0");
    assertThat(CostFormatter.format(-100L)).isEqualTo("0");
  }

  @ParameterizedTest
  @CsvSource({
    "12345678900000, '12조 3456억 7890만'",
    "500000000000, '5000억'",
    "50000000, '5000만'",
    "1234567, '123만'",
    "10000, '1만'",
    "100000000, '1억'",
    "1000000000000, '1조'",
  })
  @DisplayName("한국식 금액 포맷팅 (조/억/만)")
  void format_korean_currency(long input, String expected) {
    assertThat(CostFormatter.format(input)).isEqualTo(expected);
    assertThat(CostFormatter.format(BigDecimal.valueOf(input))).isEqualTo(expected);
  }

  @Test
  @DisplayName("만 단위 미만은 원문 그대로 반환")
  void below_man_unit_returns_plain_number() {
    assertThat(CostFormatter.format(9999L)).isEqualTo("0");
    assertThat(CostFormatter.format(1L)).isEqualTo("0");
  }

  @ParameterizedTest
  @CsvSource({
    "12345678900000, '12.3조'",
    "150000000000, '1500억'",
    "12345678, '1234.6만'",
    "12345, '1.2만'",
  })
  @DisplayName("간략화된 표기 (가장 큰 단위만)")
  void formatCompact_returns_largest_unit(long input, String expected) {
    assertThat(CostFormatter.formatCompact(BigDecimal.valueOf(input))).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "12345678900000, '12,345,678,900,000'",
    "500000000000, '500,000,000,000'",
    "1234567, '1,234,567'",
  })
  @DisplayName("천 단위 콤마 포맷")
  void formatWithComma_returns_comma_separated(long input, String expected) {
    assertThat(CostFormatter.formatWithComma(BigDecimal.valueOf(input))).isEqualTo(expected);
  }

  @Test
  @DisplayName("mixed units: 조+억+만 모두 포함")
  void mixed_units() {
    BigDecimal cost = new BigDecimal("12345678901234"); // 12조 3456억 7890만 1234
    assertThat(CostFormatter.format(cost)).isEqualTo("12조 3456억 7890만");
  }

  @Test
  @DisplayName("rounding: 소수점 반올림 처리")
  void rounding_half_up() {
    BigDecimal cost = new BigDecimal("12345.67"); // 만 단위 미만이므로 '0'
    assertThat(CostFormatter.format(cost)).isEqualTo("1만");

    BigDecimal cost2 = new BigDecimal("9999.5"); // 0.5 반올림 -> 10000 -> 1만
    assertThat(CostFormatter.format(cost2)).isEqualTo("1만");
  }
}
