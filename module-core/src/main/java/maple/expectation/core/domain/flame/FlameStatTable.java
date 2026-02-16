package maple.expectation.core.domain.flame;

import java.util.EnumMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 환생의 불꽃 추가옵션 수치 테이블
 *
 * <p>장비 레벨과 단계(stage 1-7)에 따른 추가옵션 수치를 정의한다. 방어구/악세서리와 무기는 서로 다른 테이블을 사용하며, 무기의 ATT/MAG는 기본 공격력 기반
 * 공식으로 계산한다.
 *
 * <p>레벨 버킷 매핑: 130/135 -> 130, 140/145 -> 140, 나머지는 동일
 */
public final class FlameStatTable {

  private FlameStatTable() {}

  private static final int STAGES = 7;

  /** armor[level][optionType] = Integer[7] (stage 1-7, 0-indexed) null element = 해당 단계 미존재 */
  private static final NavigableMap<Integer, Map<FlameOptionType, Integer[]>> ARMOR_TABLE =
      new TreeMap<>();

  static {
    initLevel100();
    initLevel110();
    initLevel120();
    initLevel130();
    initLevel140();
    initLevel150();
    initLevel160();
    initLevel170();
    initLevel180();
    initLevel200();
    initLevel250();
  }

  // ------------------------------------------------------------------
  // Public API
  // ------------------------------------------------------------------

  /**
   * 방어구/악세서리 추가옵션 수치 조회
   *
   * @param option 옵션 타입
   * @param level 장비 레벨
   * @param stage 단계 (1-7)
   * @return 수치 (null = 해당 단계/옵션 미존재)
   */
  public static Integer getArmorValue(FlameOptionType option, int level, int stage) {
    int bucket = toBucket(level);
    Map<FlameOptionType, Integer[]> levelTable = ARMOR_TABLE.get(bucket);
    if (levelTable == null) {
      return null;
    }

    Integer[] stages = levelTable.get(option);
    if (stages == null) {
      return null;
    }

    int idx = stage - 1;
    if (idx < 0 || idx >= STAGES) {
      return null;
    }
    return stages[idx];
  }

  /**
   * 무기 공격력/마력 보너스 계산
   *
   * <p>공식: floor((level / 40 + 1) * stage * (1 + 0.1 * (stage - 3)) * baseAtt)
   *
   * @param level 장비 레벨
   * @param stage 단계 (1-7)
   * @param baseAtt 무기 기본 공격력
   * @return 공격력/마력 보너스
   */
  public static int weaponAttBonus(int level, int stage, int baseAtt) {
    double factor = (level / 40 + 1) * stage * (1.0 + 0.1 * (stage - 3));
    return (int) Math.floor(factor * baseAtt);
  }

  /**
   * 무기 보스 데미지% 조회
   *
   * @param stage 단계 (1-7)
   * @return 보스 데미지% 수치
   */
  public static int weaponBossDmgPct(int stage) {
    return stage * 2;
  }

  /**
   * 무기 추가옵션 수치 조회 (ATT/MAG, BOSS_DMG_PCT 제외)
   *
   * <p>무기의 ATT/MAG는 {@link #weaponAttBonus}를 사용하고, BOSS_DMG_PCT는 {@link #weaponBossDmgPct}를 사용한다.
   * 나머지 옵션은 방어구 테이블과 동일하다.
   *
   * @param option 옵션 타입
   * @param level 장비 레벨
   * @param stage 단계 (1-7)
   * @return 수치 (null = 해당 단계/옵션 미존재)
   */
  public static Integer getWeaponValue(FlameOptionType option, int level, int stage) {
    return getArmorValue(option, level, stage);
  }

  // ------------------------------------------------------------------
  // Level bucket mapping
  // ------------------------------------------------------------------

  private static int toBucket(int level) {
    if (level >= 250) return 250;
    if (level >= 200) return 200;
    if (level >= 180) return 180;
    if (level >= 170) return 170;
    if (level >= 160) return 160;
    if (level >= 150) return 150;
    if (level >= 140) return 140;
    if (level >= 130) return 130;
    if (level >= 120) return 120;
    if (level >= 110) return 110;
    return 100;
  }

  // ------------------------------------------------------------------
  // Table initialization helpers
  // ------------------------------------------------------------------

  private static Integer[] stages(Integer... values) {
    if (values.length != STAGES) {
      throw new IllegalArgumentException(
          "Expected " + STAGES + " stage values, got " + values.length);
    }
    return values;
  }

  private static Integer[] linearStages(int base) {
    return stages(base, base * 2, base * 3, base * 4, base * 5, base * 6, base * 7);
  }

  private static Map<FlameOptionType, Integer[]> newOptionMap() {
    return new EnumMap<>(FlameOptionType.class);
  }

  private static void putSingleStats(Map<FlameOptionType, Integer[]> map, Integer[] values) {
    map.put(FlameOptionType.STR, values);
    map.put(FlameOptionType.DEX, values);
    map.put(FlameOptionType.INT, values);
    map.put(FlameOptionType.LUK, values);
  }

  private static void putCompositeStats(Map<FlameOptionType, Integer[]> map, Integer[] values) {
    map.put(FlameOptionType.STR_DEX, values);
    map.put(FlameOptionType.STR_INT, values);
    map.put(FlameOptionType.STR_LUK, values);
    map.put(FlameOptionType.DEX_INT, values);
    map.put(FlameOptionType.DEX_LUK, values);
    map.put(FlameOptionType.INT_LUK, values);
  }

  private static void putHpMp(Map<FlameOptionType, Integer[]> map, Integer[] values) {
    map.put(FlameOptionType.MAX_HP, values);
    map.put(FlameOptionType.MAX_MP, values);
  }

  private static void putAttMag(Map<FlameOptionType, Integer[]> map, Integer[] values) {
    map.put(FlameOptionType.ATT, values);
    map.put(FlameOptionType.MAG, values);
  }

  /** 100~150제 공통 옵션 (ATT/MAG = 1~7 선형, ALLSTAT/DMG/BOSS_DMG/LEVEL_REDUCE 동일) */
  private static void putStandardOptions(Map<FlameOptionType, Integer[]> map) {
    putAttMag(map, linearStages(1));
    map.put(FlameOptionType.ALLSTAT_PCT, linearStages(1));
    map.put(FlameOptionType.DMG_PCT, linearStages(1));
    map.put(FlameOptionType.BOSS_DMG_PCT, stages(2, 4, 6, 8, 10, 6, 7));
    map.put(FlameOptionType.LEVEL_REDUCE, linearStages(5));
    putSpeedJump(map);
  }

  private static void putSpeedJump(Map<FlameOptionType, Integer[]> map) {
    map.put(FlameOptionType.SPEED, linearStages(1));
    map.put(FlameOptionType.JUMP, linearStages(1));
  }

  // ------------------------------------------------------------------
  // Level-specific initialization
  // ------------------------------------------------------------------

  private static void initLevel100() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(6));
    putCompositeStats(map, linearStages(3));
    putHpMp(map, linearStages(300));
    map.put(FlameOptionType.DEF, linearStages(6));
    putStandardOptions(map);
    ARMOR_TABLE.put(100, map);
  }

  private static void initLevel110() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(6));
    putCompositeStats(map, linearStages(3));
    putHpMp(map, linearStages(330));
    map.put(FlameOptionType.DEF, linearStages(6));
    putStandardOptions(map);
    ARMOR_TABLE.put(110, map);
  }

  private static void initLevel120() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(7));
    putCompositeStats(map, linearStages(4));
    putHpMp(map, linearStages(360));
    map.put(FlameOptionType.DEF, linearStages(7));
    putStandardOptions(map);
    ARMOR_TABLE.put(120, map);
  }

  private static void initLevel130() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(7));
    putCompositeStats(map, linearStages(4));
    putHpMp(map, linearStages(390));
    map.put(FlameOptionType.DEF, linearStages(7));
    putStandardOptions(map);
    ARMOR_TABLE.put(130, map);
  }

  private static void initLevel140() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(8));
    putCompositeStats(map, linearStages(4));
    putHpMp(map, linearStages(420));
    map.put(FlameOptionType.DEF, linearStages(8));
    putStandardOptions(map);
    ARMOR_TABLE.put(140, map);
  }

  private static void initLevel150() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(8));
    putCompositeStats(map, linearStages(4));
    putHpMp(map, linearStages(450));
    map.put(FlameOptionType.DEF, linearStages(8));
    putStandardOptions(map);
    ARMOR_TABLE.put(150, map);
  }

  private static void initLevel160() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, stages(null, null, 27, 36, 45, 54, 63));
    putCompositeStats(map, stages(null, null, 15, 20, 25, 30, 35));
    putHpMp(map, stages(null, null, 1440, 1920, 2400, 2880, 3360));
    putAttMag(map, stages(null, null, 3, 4, 5, 6, 7));
    map.put(FlameOptionType.DEF, stages(null, null, 27, 36, 45, 54, 63));
    map.put(FlameOptionType.ALLSTAT_PCT, stages(null, null, 3, 4, 5, 6, 7));
    map.put(FlameOptionType.SPEED, stages(null, null, 3, 4, 5, 6, 7));
    map.put(FlameOptionType.JUMP, stages(null, null, 3, 4, 5, 6, 7));
    map.put(FlameOptionType.LEVEL_REDUCE, stages(null, null, 15, 20, 25, 30, 35));
    // 160제: DMG_PCT, BOSS_DMG_PCT 없음
    ARMOR_TABLE.put(160, map);
  }

  private static void initLevel170() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, stages(9, 18, 27, 36, 45, null, null));
    putCompositeStats(map, stages(5, 10, 15, 20, 25, null, null));
    putHpMp(map, stages(510, 1020, 1530, 2040, 2550, null, null));
    map.put(FlameOptionType.ATT, stages(9, 20, 32, 47, 64, null, null));
    map.put(FlameOptionType.MAG, stages(9, 20, 32, 47, 64, null, null));
    map.put(FlameOptionType.DEF, stages(9, 18, 27, 36, 45, null, null));
    map.put(FlameOptionType.ALLSTAT_PCT, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.DMG_PCT, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.BOSS_DMG_PCT, stages(2, 4, 6, 8, 10, null, null));
    map.put(FlameOptionType.LEVEL_REDUCE, stages(5, 10, 15, 20, 25, null, null));
    map.put(FlameOptionType.SPEED, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.JUMP, stages(1, 2, 3, 4, 5, null, null));
    ARMOR_TABLE.put(170, map);
  }

  private static void initLevel180() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, stages(10, 20, 30, 40, 50, null, null));
    putCompositeStats(map, stages(5, 10, 15, 20, 25, null, null));
    putHpMp(map, stages(540, 1080, 1620, 2160, 2700, null, null));
    map.put(FlameOptionType.ATT, stages(11, 23, 38, 56, 76, null, null));
    map.put(FlameOptionType.MAG, stages(11, 23, 38, 56, 76, null, null));
    map.put(FlameOptionType.DEF, stages(10, 20, 30, 40, 50, null, null));
    map.put(FlameOptionType.ALLSTAT_PCT, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.DMG_PCT, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.BOSS_DMG_PCT, stages(2, 4, 6, 8, 10, null, null));
    map.put(FlameOptionType.LEVEL_REDUCE, stages(5, 10, 15, 20, 25, null, null));
    map.put(FlameOptionType.SPEED, stages(1, 2, 3, 4, 5, null, null));
    map.put(FlameOptionType.JUMP, stages(1, 2, 3, 4, 5, null, null));
    ARMOR_TABLE.put(180, map);
  }

  private static void initLevel200() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(11));
    putCompositeStats(map, linearStages(6));
    putHpMp(map, linearStages(600));
    map.put(FlameOptionType.DEF, linearStages(11));
    putAttMag(map, linearStages(1));
    map.put(FlameOptionType.ALLSTAT_PCT, linearStages(1));
    map.put(FlameOptionType.DMG_PCT, linearStages(1));
    map.put(FlameOptionType.BOSS_DMG_PCT, stages(2, 4, 6, 8, 10, 12, 14));
    map.put(FlameOptionType.LEVEL_REDUCE, linearStages(5));
    putSpeedJump(map);
    ARMOR_TABLE.put(200, map);
  }

  private static void initLevel250() {
    Map<FlameOptionType, Integer[]> map = newOptionMap();
    putSingleStats(map, linearStages(12));
    putCompositeStats(map, linearStages(7));
    putHpMp(map, linearStages(700));
    map.put(FlameOptionType.DEF, linearStages(12));
    putAttMag(map, linearStages(1));
    map.put(FlameOptionType.ALLSTAT_PCT, linearStages(1));
    map.put(FlameOptionType.DMG_PCT, linearStages(1));
    map.put(FlameOptionType.BOSS_DMG_PCT, stages(2, 4, 6, 8, 10, 6, 7));
    map.put(FlameOptionType.LEVEL_REDUCE, linearStages(5));
    putSpeedJump(map);
    ARMOR_TABLE.put(250, map);
  }
}
