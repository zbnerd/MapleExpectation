package maple.expectation.external.dto.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true) // 정의하지 않은 필드가 와도 에러 안 나게 무시
public class EquipmentResponse {

  // ==========================================
  // 1️⃣ Level 1: 응답 최상위 (Root)
  // ==========================================
  @JsonProperty("date")
  private String date;

  @JsonProperty("character_gender")
  private String characterGender;

  @JsonProperty("character_class")
  private String characterClass;

  @JsonProperty("preset_no")
  private Integer presetNo;

  // --- 메인 장비 리스트 ---
  @JsonProperty("item_equipment")
  private List<ItemEquipment> itemEquipment;

  // --- 프리셋 리스트 (1~3) ---
  @JsonProperty("item_equipment_preset_1")
  private List<ItemEquipment> itemEquipmentPreset1;

  @JsonProperty("item_equipment_preset_2")
  private List<ItemEquipment> itemEquipmentPreset2;

  @JsonProperty("item_equipment_preset_3")
  private List<ItemEquipment> itemEquipmentPreset3;

  // --- 특수 장비 (에반, 메카닉 등) ---
  // 일반 장비와 구조가 같으므로 ItemEquipment 재사용
  @JsonProperty("dragon_equipment")
  private List<ItemEquipment> dragonEquipment;

  @JsonProperty("mechanic_equipment")
  private List<ItemEquipment> mechanicEquipment;

  // --- 칭호 ---
  @JsonProperty("title")
  private Title title;

  // ==========================================
  // 2️⃣ Level 2: 아이템 1개 상세 정보 (ItemEquipment)
  // ==========================================
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ItemEquipment {
    @JsonProperty("item_equipment_part")
    private String itemEquipmentPart; // 장착 부위 (모자, 상의 등)

    @JsonProperty("item_equipment_slot")
    private String itemEquipmentSlot;

    @JsonProperty("item_name")
    private String itemName;

    @JsonProperty("item_icon")
    private String itemIcon;

    @JsonProperty("item_description")
    private String itemDescription;

    @JsonProperty("item_shape_name")
    private String itemShapeName;

    @JsonProperty("item_shape_icon")
    private String itemShapeIcon;

    @JsonProperty("item_gender")
    private String itemGender;

    // --- 📊 핵심: 옵션 정보들 (전부 ItemOption 클래스 재사용) ---

    @JsonProperty("item_total_option")
    private ItemOption totalOption; // 최종 옵션

    @JsonProperty("item_base_option")
    private ItemOption baseOption; // 깡통 옵션

    @JsonProperty("item_add_option")
    private ItemOption addOption; // 추옵

    @JsonProperty("item_etc_option")
    private ItemOption etcOption; // 작(주문서) 상태

    @JsonProperty("item_starforce_option")
    private ItemOption starforceOption; // 스타포스로 오르는 수치

    @JsonProperty("item_exceptional_option")
    private ItemOption exceptionalOption; // 익셉셔널 강화 수치

    // --- ✨ 잠재능력 (윗잠) ---
    @JsonProperty("potential_option_grade")
    private String potentialOptionGrade; // 등급 (레전드리 등)

    @JsonProperty("potential_option_1")
    private String potentialOption1;

    @JsonProperty("potential_option_2")
    private String potentialOption2;

    @JsonProperty("potential_option_3")
    private String potentialOption3;

    // --- ✨ 에디셔널 (아랫잠) ---
    @JsonProperty("additional_potential_option_grade")
    private String additionalPotentialOptionGrade;

    @JsonProperty("additional_potential_option_1")
    private String additionalPotentialOption1;

    @JsonProperty("additional_potential_option_2")
    private String additionalPotentialOption2;

    @JsonProperty("additional_potential_option_3")
    private String additionalPotentialOption3;

    // --- 기타 강화 정보 ---
    @JsonProperty("equipment_level_increase")
    private String equipmentLevelIncrease; // 착감 등

    @JsonProperty("growth_exp")
    private String growthExp;

    @JsonProperty("growth_level")
    private String growthLevel;

    @JsonProperty("scroll_upgrade")
    private String scrollUpgrade; // 업횟

    @JsonProperty("cuttable_count")
    private String cuttableCount; // 가횟

    @JsonProperty("golden_hammer_flag")
    private String goldenHammerFlag;

    @JsonProperty("scroll_resilience_count")
    private String scrollResilienceCount; // 복구 가능 횟수

    @JsonProperty("scroll_upgradeable_count")
    private String scrollUpgradeableCount; // 황망 등 남은 횟수

    @JsonProperty("soul_name")
    private String soulName;

    @JsonProperty("soul_option")
    private String soulOption;

    @JsonProperty("starforce")
    private String starforce; // ★ 스타포스 수치

    @JsonProperty("starforce_scroll_flag")
    private String starforceScrollFlag; // 슈페리얼 등 여부

    @JsonProperty("special_ring_level")
    private String specialRingLevel; // 시드링 레벨

    @JsonProperty("date_expire")
    private String dateExpire;
  }

  // ==========================================
  // 3️⃣ Level 3: 옵션 수치 상세 (ItemOption)
  // ==========================================
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ItemOption {
    // 안전하게 String으로 받고, 나중에 Integer.parseInt() 사용 권장

    @JsonProperty("str")
    private String str;

    @JsonProperty("dex")
    private String dex;

    @JsonProperty("int") // ⚠️ 중요: 자바 예약어 'int'와 충돌 방지
    private String intValue;

    @JsonProperty("luk")
    private String luk;

    @JsonProperty("max_hp")
    private String maxHp;

    @JsonProperty("max_mp")
    private String maxMp;

    @JsonProperty("attack_power")
    private String attackPower;

    @JsonProperty("magic_power")
    private String magicPower;

    @JsonProperty("armor")
    private String armor;

    @JsonProperty("speed")
    private String speed;

    @JsonProperty("jump")
    private String jump;

    @JsonProperty("boss_damage")
    private String bossDamage;

    @JsonProperty("ignore_monster_armor") // 방무
    private String ignoreMonsterArmor;

    @JsonProperty("all_stat")
    private String allStat; // 올스탯 %

    @JsonProperty("damage") // 데미지 %
    private String damage;

    @JsonProperty("equipment_level_decrease") // 착감
    private String equipmentLevelDecrease;

    @JsonProperty("max_hp_rate")
    private String maxHpRate;

    @JsonProperty("max_mp_rate")
    private String maxMpRate;

    @JsonProperty("base_equipment_level") // 기본 옵션에만 존재
    private String baseEquipmentLevel;

    @JsonProperty("exceptional_upgrade") // 익셉셔널에만 존재 (1강 등)
    private String exceptionalUpgrade;
  }

  // ==========================================
  // 4️⃣ 번외: 칭호 (Title)
  // ==========================================
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Title {
    @JsonProperty("title_name")
    private String titleName;

    @JsonProperty("title_icon")
    private String titleIcon;

    @JsonProperty("title_description")
    private String titleDescription;

    @JsonProperty("date_expire")
    private String dateExpire;

    @JsonProperty("date_option_expire")
    private String dateOptionExpire;
  }
}
