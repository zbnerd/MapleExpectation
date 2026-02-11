package maple.expectation.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * StatType enum 순수 유닛 테스트 (개선 버전)
 *
 * <p>테스트 피라미드 최하위 계층: Spring/DB 없이 순수 JUnit5 + AssertJ
 *
 * <p>테스트 속도: ~10ms (완전히 격리된 enum 테스트)
 */
@DisplayName("StatType enum 순수 유닛 테스트 (개선)")
class StatTypeTestNew {

  // ==================== findType (기존 방식, non-percent만) ====================

  @ParameterizedTest
  @CsvSource({
    "'STR +12', STR",
    "'DEX +30', DEX",
    "'INT +100', INT",
    "'LUK +50', LUK",
    "'공격력 +50', ATTACK_POWER",
    "'마력 +30', MAGIC_POWER",
    "'재사용 대기시간 -2초', COOLDOWN_REDUCTION",
    // Note: BOSS_DAMAGE and IGNORE_DEFENSE are percent-only types, so findType() returns UNKNOWN
  })
  @DisplayName("findType: non-percent 스탯 매칭 (기존 방식)")
  void findType_matches_non_percent_stats(String option, StatType expected) {
    assertThat(StatType.findType(option)).isEqualTo(expected);
  }

  @Test
  @DisplayName("findType: 보공/방무는 % 스탯이라 UNKNOWN 반환")
  void findType_boss_damage_ignore_defense_returns_unknown() {
    assertThat(StatType.findType("보스 몬스터 공격 시 데미지 +30%")).isEqualTo(StatType.UNKNOWN);
    assertThat(StatType.findType("몬스터 방어율 무시 +30%")).isEqualTo(StatType.UNKNOWN);
  }

  @Test
  @DisplayName("findType: 퍼센트 스탯은 UNKNOWN 반환 (기존 방식 제한)")
  void findType_percent_returns_unknown() {
    assertThat(StatType.findType("STR +12%")).isEqualTo(StatType.UNKNOWN);
    assertThat(StatType.findType("올스탯 +9%")).isEqualTo(StatType.UNKNOWN);
  }

  @ParameterizedTest
  @ValueSource(strings = {"피격 시 10% 확률로 데미지 무시", "공격 시 5% 확률로 오토스틸"})
  @DisplayName("findType: 특수 옵션은 UNKNOWN 반환")
  void findType_special_options_return_unknown(String option) {
    assertThat(StatType.findType(option)).isEqualTo(StatType.UNKNOWN);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("findType: null 또는 빈 문자열은 UNKNOWN 반환")
  void findType_null_or_empty_returns_unknown(String option) {
    assertThat(StatType.findType(option)).isEqualTo(StatType.UNKNOWN);
  }

  // ==================== findTypeWithUnit (퍼센트/플랫 자동 판별) ====================

  @ParameterizedTest
  @CsvSource({
    "'STR +12%', STR_PERCENT",
    "'DEX +9%', DEX_PERCENT",
    "'INT +15%', INT_PERCENT",
    "'LUK +12%', LUK_PERCENT",
    "'올스탯 +9%', ALLSTAT_PERCENT",
    "'공격력 +30%', ATTACK_POWER_PERCENT",
    "'마력 +30%', MAGIC_POWER_PERCENT",
  })
  @DisplayName("findTypeWithUnit: percent 스탯 매칭")
  void findTypeWithUnit_matches_percent_stats(String option, StatType expected) {
    assertThat(StatType.findTypeWithUnit(option)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "'STR +30', STR",
    "'DEX +50', DEX",
    "'공격력 +100', ATTACK_POWER",
    "'재사용 대기시간 -2초', COOLDOWN_REDUCTION",
  })
  @DisplayName("findTypeWithUnit: 플랫 스탯 매칭")
  void findTypeWithUnit_matches_flat_stats(String option, StatType expected) {
    assertThat(StatType.findTypeWithUnit(option)).isEqualTo(expected);
  }

  @Test
  @DisplayName("findTypeWithUnit: 보공/방무는 %만 있어도 매칭")
  void findTypeWithUnit_boss_damage_matches_percent_only() {
    assertThat(StatType.findTypeWithUnit("보스 몬스터 공격 시 데미지 +30%")).isEqualTo(StatType.BOSS_DAMAGE);
    assertThat(StatType.findTypeWithUnit("몬스터 방어율 무시 +30%")).isEqualTo(StatType.IGNORE_DEFENSE);
  }

  // ==================== findAllTypes (복합 옵션 대응) ====================

  @Test
  @DisplayName("findAllTypes: 단일 스탯 매칭")
  void findAllTypes_single_stat() {
    assertThat(StatType.findAllTypes("STR +12%")).containsExactly(StatType.STR_PERCENT);
    assertThat(StatType.findAllTypes("공격력 +30")).containsExactly(StatType.ATTACK_POWER);
  }

  @Test
  @DisplayName("findAllTypes: 복합 스탯 매칭 (STR/DEX)")
  void findAllTypes_compound_stats() {
    List<StatType> result = StatType.findAllTypes("STR/DEX +6%");
    assertThat(result).containsExactly(StatType.STR_PERCENT, StatType.DEX_PERCENT);
  }

  @Test
  @DisplayName("findAllTypes: 올스탯 매칭")
  void findAllTypes_all_stat() {
    assertThat(StatType.findAllTypes("올스탯 +9%")).containsExactly(StatType.ALLSTAT_PERCENT);
  }

  @Test
  @DisplayName("findAllTypes: longest-first 매칭 (보공 > 데미지)")
  void findAllTypes_longest_first_matching() {
    // "보스 몬스터 공격 시 데미지"는 BOSS_DAMAGE로만 매칭 (DAMAGE 오탐 방지)
    List<StatType> result = StatType.findAllTypes("보스 몬스터 공격 시 데미지 +30%");
    assertThat(result).containsExactly(StatType.BOSS_DAMAGE);
    assertThat(result).doesNotContain(StatType.DAMAGE);
  }

  @Test
  @DisplayName("findAllTypes: 레벨당 스탯 최우선 감지")
  void findAllTypes_level_stat_first() {
    // "캐릭터 기준 9레벨 당 STR +1" → LEVEL_STR (STR 아님)
    List<StatType> result = StatType.findAllTypes("캐릭터 기준 9레벨 당 STR +1");
    assertThat(result).containsExactly(StatType.LEVEL_STR);
  }

  @ParameterizedTest
  @ValueSource(strings = {"피격 시 10% 확률로 데미지 무시", "공격 시 5% 확률로 오토스틸"})
  @DisplayName("findAllTypes: 특수 옵션은 빈 리스트 반환 (findAllTypesOrEmpty 사용)")
  void findAllTypes_special_options_return_empty(String option) {
    // findAllTypes()는 매칭 실패 시 [UNKNOWN] 반환
    // 빈 리스트가 필요하면 findAllTypesOrEmpty() 사용
    assertThat(StatType.findAllTypesOrEmpty(option)).isEmpty();
    assertThat(StatType.findAllTypes(option)).containsExactly(StatType.UNKNOWN);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("findAllTypes: null 또는 빈 문자열은 UNKNOWN 리스트 반환")
  void findAllTypes_null_or_empty_returns_unknown(String option) {
    // findAllTypes()는 매칭 실패 시 [UNKNOWN] 반환
    // 빈 리스트가 필요하면 findAllTypesOrEmpty() 사용
    assertThat(StatType.findAllTypes(option)).containsExactly(StatType.UNKNOWN);
    assertThat(StatType.findAllTypesOrEmpty(option)).isEmpty();
  }

  @Test
  @DisplayName("findAllTypes: 매칭 실패 시 UNKNOWN 반환")
  void findAllTypes_no_match_returns_unknown() {
    assertThat(StatType.findAllTypes("잘못된 옵션 문자열")).containsExactly(StatType.UNKNOWN);
  }

  // ==================== findAllTypesStrict (Fail-Fast) ====================

  @Test
  @DisplayName("findAllTypesStrict: 매칭 실패 시 예외 발생")
  void findAllTypesStrict_no_match_throws_exception() {
    assertThatThrownBy(() -> StatType.findAllTypesStrict("잘못된 옵션 문자열"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("스탯 타입 매칭 실패");
  }

  @Test
  @DisplayName("findAllTypesStrict: 매칭 성공 시 리스트 반환")
  void findAllTypesStrict_match_returns_list() {
    List<StatType> result = StatType.findAllTypesStrict("STR +12%");
    assertThat(result).containsExactly(StatType.STR_PERCENT);
  }

  // ==================== 속성 테스트 ====================

  @Test
  @DisplayName("percent 속성: 퍼센트 스탯 확인")
  void percent_property() {
    assertThat(StatType.STR_PERCENT.isPercent()).isTrue();
    assertThat(StatType.STR.isPercent()).isFalse();
    assertThat(StatType.BOSS_DAMAGE.isPercent()).isTrue();
    assertThat(StatType.ATTACK_POWER.isPercent()).isFalse();
  }

  @Test
  @DisplayName("individualStat 속성: 개별 스탯 확인")
  void individualStat_property() {
    assertThat(StatType.STR.isIndividualStat()).isTrue();
    assertThat(StatType.DEX.isIndividualStat()).isTrue();
    assertThat(StatType.INT.isIndividualStat()).isTrue();
    assertThat(StatType.LUK.isIndividualStat()).isTrue();

    assertThat(StatType.ALL_STAT.isIndividualStat()).isFalse();
    assertThat(StatType.ATTACK_POWER.isIndividualStat()).isFalse();
  }

  @Test
  @DisplayName("keyword 속성: 키워드 확인")
  void keyword_property() {
    assertThat(StatType.STR.getKeyword()).isEqualTo("STR");
    assertThat(StatType.BOSS_DAMAGE.getKeyword()).isEqualTo("보스 몬스터 공격 시 데미지");
    assertThat(StatType.COOLDOWN_REDUCTION.getKeyword()).isEqualTo("재사용 대기시간");
  }
}
