package maple.expectation.service.v2.flame;

/**
 * 환생의 불꽃 장비 분류
 *
 * <p>보스 드랍 장비: 4줄 고정 그외 장비: 1~4줄 균등
 */
public enum FlameEquipCategory {
  BOSS_WEAPON(true, true, 4),
  BOSS_ARMOR(true, false, 4),
  OTHER_WEAPON(false, true, -1), // -1 = 1~4 uniform
  OTHER_ARMOR(false, false, -1);

  private final boolean bossDrop;
  private final boolean weapon;
  private final int fixedLineCount; // 4 for boss, -1 for other (1~4 uniform)

  FlameEquipCategory(boolean bossDrop, boolean weapon, int fixedLineCount) {
    this.bossDrop = bossDrop;
    this.weapon = weapon;
    this.fixedLineCount = fixedLineCount;
  }

  public boolean isBossDrop() {
    return bossDrop;
  }

  public boolean isWeapon() {
    return weapon;
  }

  public int getFixedLineCount() {
    return fixedLineCount;
  }

  public static FlameEquipCategory of(boolean bossDrop, boolean isWeapon) {
    if (bossDrop) {
      return isWeapon ? BOSS_WEAPON : BOSS_ARMOR;
    }
    return isWeapon ? OTHER_WEAPON : OTHER_ARMOR;
  }
}
